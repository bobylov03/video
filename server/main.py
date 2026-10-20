"""
Video Sync Server
=================
Сервер, который принимает видео с Android-приложения и показывает их в
веб-интерфейсе с разбивкой по пользователям.

Построен на Starlette (это тот же фундамент, на котором работает FastAPI).
Пароли хешируются встроенным модулем hashlib — лишних зависимостей нет.

Возможности:
  - Регистрация и вход пользователей (пароли хранятся хешированными)
  - Токен-авторизация (JWT) для Android-приложения
  - Загрузка видео (multipart), каждое видео привязано к пользователю
  - Веб-страница со списком видео по пользователям + встроенный плеер
  - Админ (логин admin / пароль admin123) видит видео всех пользователей

Запуск:
    pip install -r requirements.txt
    python main.py
Сервер поднимется на http://0.0.0.0:8000
"""

import os
import hmac
import hashlib
import sqlite3
import uuid
import secrets
import datetime
from pathlib import Path
from contextlib import contextmanager

import jwt
from starlette.applications import Starlette
from starlette.routing import Route
from starlette.requests import Request
from starlette.responses import (
    HTMLResponse, RedirectResponse, FileResponse, JSONResponse, PlainTextResponse,
)
from jinja2 import Environment, BaseLoader

# ---------------------------------------------------------------------------
# Настройки
# ---------------------------------------------------------------------------
BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data"
VIDEO_DIR = DATA_DIR / "videos"
DB_PATH = DATA_DIR / "app.db"
VIDEO_DIR.mkdir(parents=True, exist_ok=True)

# В реальном проде задайте свой ключ через переменную окружения SECRET_KEY!
SECRET_KEY = os.environ.get("SECRET_KEY", "change-me-" + secrets.token_hex(16))
JWT_ALGO = "HS256"
TOKEN_TTL_DAYS = 365  # токен приложения живёт долго, чтобы не разлогинивать телефон


# ---------------------------------------------------------------------------
# Хеширование паролей (встроенный pbkdf2 — безопасно и без зависимостей)
# ---------------------------------------------------------------------------
def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 200_000)
    return f"pbkdf2_sha256$200000${salt.hex()}${dk.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        algo, iters, salt_hex, hash_hex = stored.split("$")
        dk = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt_hex), int(iters))
        return hmac.compare_digest(dk.hex(), hash_hex)
    except Exception:
        return False


# ---------------------------------------------------------------------------
# База данных (SQLite)
# ---------------------------------------------------------------------------
@contextmanager
def db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def now():
    return datetime.datetime.utcnow().isoformat()


def init_db():
    with db() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                username   TEXT UNIQUE NOT NULL,
                password   TEXT NOT NULL,
                is_admin   INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS videos (
                id           TEXT PRIMARY KEY,
                user_id      INTEGER NOT NULL,
                filename     TEXT NOT NULL,
                orig_name    TEXT,
                size_bytes   INTEGER NOT NULL DEFAULT 0,
                uploaded_at  TEXT NOT NULL,
                FOREIGN KEY (user_id) REFERENCES users(id)
            );
            """
        )
        if conn.execute("SELECT id FROM users WHERE username='admin'").fetchone() is None:
            conn.execute(
                "INSERT INTO users (username, password, is_admin, created_at) VALUES (?,?,1,?)",
                ("admin", hash_password("admin123"), now()),
            )


# ---------------------------------------------------------------------------
# Токены и определение пользователя
# ---------------------------------------------------------------------------
def make_token(user_id: int) -> str:
    payload = {
        "sub": str(user_id),
        "exp": datetime.datetime.utcnow() + datetime.timedelta(days=TOKEN_TTL_DAYS),
    }
    return jwt.encode(payload, SECRET_KEY, algorithm=JWT_ALGO)


def user_from_token(token: str):
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[JWT_ALGO])
        uid = int(payload["sub"])
    except Exception:
        return None
    with db() as conn:
        return conn.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()


def api_user(request: Request):
    """Пользователь по заголовку Authorization: Bearer <token> (для приложения)."""
    auth = request.headers.get("authorization", "")
    if not auth.lower().startswith("bearer "):
        return None
    return user_from_token(auth[7:].strip())


def web_user(request: Request):
    """Пользователь по cookie (для браузера)."""
    token = request.cookies.get("token")
    return user_from_token(token) if token else None


# ---------------------------------------------------------------------------
# API endpoints (их вызывает Android-приложение)
# ---------------------------------------------------------------------------
async def api_register(request: Request):
    form = await request.form()
    username = (form.get("username") or "").strip().lower()
    password = form.get("password") or ""
    if len(username) < 3 or len(password) < 4:
        return JSONResponse({"detail": "Логин от 3 символов, пароль от 4 символов"}, status_code=400)
    with db() as conn:
        if conn.execute("SELECT id FROM users WHERE username=?", (username,)).fetchone():
            return JSONResponse({"detail": "Такой пользователь уже есть"}, status_code=409)
        cur = conn.execute(
            "INSERT INTO users (username, password, is_admin, created_at) VALUES (?,?,0,?)",
            (username, hash_password(password), now()),
        )
        uid = cur.lastrowid
    return JSONResponse({"token": make_token(uid), "username": username})


async def api_login(request: Request):
    form = await request.form()
    username = (form.get("username") or "").strip().lower()
    password = form.get("password") or ""
    with db() as conn:
        row = conn.execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
    if row is None or not verify_password(password, row["password"]):
        return JSONResponse({"detail": "Неверный логин или пароль"}, status_code=401)
    return JSONResponse({"token": make_token(row["id"]), "username": username})


async def api_upload(request: Request):
    user = api_user(request)
    if user is None:
        return JSONResponse({"detail": "Нужен токен"}, status_code=401)

    form = await request.form()
    upload = form.get("file")
    if upload is None or not hasattr(upload, "filename"):
        return JSONResponse({"detail": "Нет файла"}, status_code=400)
    orig_name = form.get("orig_name") or upload.filename

    vid = uuid.uuid4().hex
    ext = os.path.splitext(upload.filename or "video.mp4")[1] or ".mp4"
    disk_name = f"{vid}{ext}"
    dest = VIDEO_DIR / disk_name

    size = 0
    with open(dest, "wb") as out:
        while True:
            chunk = await upload.read(1024 * 1024)  # по 1 МБ
            if not chunk:
                break
            out.write(chunk)
            size += len(chunk)

    with db() as conn:
        conn.execute(
            "INSERT INTO videos (id, user_id, filename, orig_name, size_bytes, uploaded_at) "
            "VALUES (?,?,?,?,?,?)",
            (vid, user["id"], disk_name, orig_name, size, now()),
        )
    return JSONResponse({"ok": True, "id": vid, "size": size})


async def api_my_videos(request: Request):
    user = api_user(request)
    if user is None:
        return JSONResponse({"detail": "Нужен токен"}, status_code=401)
    with db() as conn:
        rows = conn.execute(
            "SELECT id, orig_name, uploaded_at FROM videos WHERE user_id=? ORDER BY uploaded_at DESC",
            (user["id"],),
        ).fetchall()
    return JSONResponse({"videos": [dict(r) for r in rows]})


# ---------------------------------------------------------------------------
# Раздача видеофайлов (для плеера в браузере)
# ---------------------------------------------------------------------------
async def serve_video(request: Request):
    viewer = web_user(request)
    if viewer is None:
        return RedirectResponse("/login")
    video_id = request.path_params["video_id"]
    with db() as conn:
        row = conn.execute("SELECT * FROM videos WHERE id=?", (video_id,)).fetchone()
    if row is None:
        return PlainTextResponse("Видео не найдено", status_code=404)
    if not viewer["is_admin"] and row["user_id"] != viewer["id"]:
        return PlainTextResponse("Нет доступа", status_code=403)
    path = VIDEO_DIR / row["filename"]
    if not path.exists():
        return PlainTextResponse("Файл отсутствует на диске", status_code=404)
    return FileResponse(path, media_type="video/mp4")


# ---------------------------------------------------------------------------
# Веб-интерфейс (HTML)
# ---------------------------------------------------------------------------
jenv = Environment(loader=BaseLoader())
PAGE = jenv.from_string(
    """
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{{ title }}</title>
<style>
  :root { --bg:#0f1115; --card:#1a1d24; --line:#2a2e38; --text:#e7e9ee; --muted:#9aa0ac; --accent:#5b8cff; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--text);
         font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif; }
  header { display:flex; align-items:center; justify-content:space-between;
           padding:16px 24px; border-bottom:1px solid var(--line); }
  header .brand { font-weight:700; font-size:18px; }
  header a { color:var(--muted); text-decoration:none; margin-left:16px; font-size:14px; }
  header a:hover { color:var(--text); }
  .wrap { max-width:1100px; margin:0 auto; padding:24px; }
  h1 { font-size:22px; margin:0 0 4px; }
  .sub { color:var(--muted); margin:0 0 24px; font-size:14px; }
  .grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(280px,1fr)); gap:16px; }
  .card { background:var(--card); border:1px solid var(--line); border-radius:12px; overflow:hidden; }
  .card video { width:100%; display:block; background:#000; aspect-ratio:16/9; object-fit:contain; }
  .card .meta { padding:12px 14px; }
  .card .meta .name { font-size:14px; word-break:break-all; }
  .card .meta .info { color:var(--muted); font-size:12px; margin-top:4px; }
  .user-block { margin-bottom:32px; }
  .user-head { display:flex; align-items:baseline; gap:10px; margin:0 0 14px;
               padding-bottom:8px; border-bottom:1px solid var(--line); }
  .user-head .u { font-size:17px; font-weight:600; }
  .user-head .c { color:var(--muted); font-size:13px; }
  .empty { color:var(--muted); padding:40px 0; text-align:center; }
  .auth { max-width:360px; margin:60px auto; background:var(--card);
          border:1px solid var(--line); border-radius:14px; padding:28px; }
  .auth h1 { text-align:center; margin-bottom:20px; }
  .auth label { display:block; font-size:13px; color:var(--muted); margin:12px 0 6px; }
  .auth input { width:100%; padding:11px 12px; border-radius:8px; border:1px solid var(--line);
                background:#0f1115; color:var(--text); font-size:15px; }
  .auth button { width:100%; margin-top:20px; padding:12px; border:none; border-radius:8px;
                 background:var(--accent); color:#fff; font-size:15px; font-weight:600; cursor:pointer; }
  .auth .alt { text-align:center; margin-top:16px; font-size:14px; color:var(--muted); }
  .auth .alt a { color:var(--accent); text-decoration:none; }
  .err { background:#3a1d1d; border:1px solid #7a2e2e; color:#ffb4b4;
         padding:10px 12px; border-radius:8px; font-size:14px; margin-bottom:8px; }
  .badge { background:var(--accent); color:#fff; font-size:11px; padding:2px 8px;
           border-radius:20px; font-weight:600; }
</style>
</head>
<body>
{% if user %}
<header>
  <div class="brand">🎬 Video Sync {% if user.is_admin %}<span class="badge">ADMIN</span>{% endif %}</div>
  <nav>
    <span style="color:var(--muted);font-size:14px">{{ user.username }}</span>
    <a href="/logout">Выйти</a>
  </nav>
</header>
{% endif %}
<div class="{{ 'wrap' if user else '' }}">
{{ body }}
</div>
</body>
</html>
"""
)


def render(title, body, user=None):
    return HTMLResponse(PAGE.render(title=title, body=body, user=user))


def video_card(v):
    size_mb = v["size_bytes"] / (1024 * 1024)
    when = v["uploaded_at"].replace("T", " ")[:16]
    name = (v["orig_name"] or "video")
    return f"""
    <div class="card">
      <video controls preload="metadata" src="/video/{v['id']}"></video>
      <div class="meta">
        <div class="name">{name}</div>
        <div class="info">{size_mb:.1f} МБ · {when}</div>
      </div>
    </div>"""


async def home(request: Request):
    user = web_user(request)
    if user is None:
        return RedirectResponse("/login")

    with db() as conn:
        if user["is_admin"]:
            users = conn.execute(
                "SELECT u.id, u.username FROM users u ORDER BY u.username"
            ).fetchall()
            parts = ["<h1>Все пользователи</h1><p class='sub'>Видео всех зарегистрированных пользователей</p>"]
            for u in users:
                vids = conn.execute(
                    "SELECT * FROM videos WHERE user_id=? ORDER BY uploaded_at DESC", (u["id"],)
                ).fetchall()
                parts.append(
                    f"<div class='user-block'><div class='user-head'>"
                    f"<span class='u'>👤 {u['username']}</span>"
                    f"<span class='c'>{len(vids)} видео</span></div>"
                )
                if vids:
                    parts.append("<div class='grid'>" + "".join(video_card(v) for v in vids) + "</div>")
                else:
                    parts.append("<div class='empty' style='padding:16px 0'>нет видео</div>")
                parts.append("</div>")
            return render("Все пользователи — Video Sync", "".join(parts), user)
        else:
            vids = conn.execute(
                "SELECT * FROM videos WHERE user_id=? ORDER BY uploaded_at DESC", (user["id"],)
            ).fetchall()
            head = f"<h1>Мои видео</h1><p class='sub'>Всего: {len(vids)}</p>"
            if not vids:
                body = head + ("<div class='empty'>Пока нет загруженных видео.<br>"
                               "Запишите видео в приложении — оно появится здесь.</div>")
            else:
                body = head + "<div class='grid'>" + "".join(video_card(v) for v in vids) + "</div>"
            return render("Мои видео — Video Sync", body, user)


def auth_form(kind, error=None):
    is_login = kind == "login"
    title = "Вход" if is_login else "Регистрация"
    action = "/login" if is_login else "/register"
    alt = ("Нет аккаунта? <a href='/register'>Зарегистрироваться</a>" if is_login
           else "Уже есть аккаунт? <a href='/login'>Войти</a>")
    err_html = f"<div class='err'>{error}</div>" if error else ""
    return f"""
    <form class="auth" method="post" action="{action}">
      <h1>🎬 {title}</h1>
      {err_html}
      <label>Логин</label>
      <input name="username" autocomplete="username" required>
      <label>Пароль</label>
      <input name="password" type="password" required>
      <button type="submit">{title}</button>
      <div class="alt">{alt}</div>
    </form>"""


async def login_page(request: Request):
    return render("Вход", auth_form("login"))


async def register_page(request: Request):
    return render("Регистрация", auth_form("register"))


async def login_submit(request: Request):
    form = await request.form()
    username = (form.get("username") or "").strip().lower()
    password = form.get("password") or ""
    with db() as conn:
        row = conn.execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
    if row is None or not verify_password(password, row["password"]):
        return render("Вход", auth_form("login", "Неверный логин или пароль"))
    resp = RedirectResponse("/", status_code=302)
    resp.set_cookie("token", make_token(row["id"]), httponly=True, max_age=60 * 60 * 24 * 365)
    return resp


async def register_submit(request: Request):
    form = await request.form()
    username = (form.get("username") or "").strip().lower()
    password = form.get("password") or ""
    if len(username) < 3 or len(password) < 4:
        return render("Регистрация", auth_form("register", "Логин от 3 символов, пароль от 4"))
    with db() as conn:
        if conn.execute("SELECT id FROM users WHERE username=?", (username,)).fetchone():
            return render("Регистрация", auth_form("register", "Такой пользователь уже есть"))
        cur = conn.execute(
            "INSERT INTO users (username, password, is_admin, created_at) VALUES (?,?,0,?)",
            (username, hash_password(password), now()),
        )
        uid = cur.lastrowid
    resp = RedirectResponse("/", status_code=302)
    resp.set_cookie("token", make_token(uid), httponly=True, max_age=60 * 60 * 24 * 365)
    return resp


async def logout(request: Request):
    resp = RedirectResponse("/login", status_code=302)
    resp.delete_cookie("token")
    return resp


# ---------------------------------------------------------------------------
# Маршруты и запуск
# ---------------------------------------------------------------------------
routes = [
    Route("/", home),
    Route("/login", login_page, methods=["GET"]),
    Route("/login", login_submit, methods=["POST"]),
    Route("/register", register_page, methods=["GET"]),
    Route("/register", register_submit, methods=["POST"]),
    Route("/logout", logout),
    Route("/video/{video_id}", serve_video),
    # API для приложения
    Route("/api/register", api_register, methods=["POST"]),
    Route("/api/login", api_login, methods=["POST"]),
    Route("/api/upload", api_upload, methods=["POST"]),
    Route("/api/my-videos", api_my_videos, methods=["GET"]),
]

init_db()  # создаём таблицы и админа при старте
app = Starlette(routes=routes)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
