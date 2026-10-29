import hashlib
import json
import secrets
import sqlite3
import time
import asyncio

from fastapi import WebSocket, WebSocketDisconnect
from starlette.concurrency import run_in_threadpool

from realtime import gestor

from contextlib import asynccontextmanager
from uuid import uuid4

from fastapi import Depends, FastAPI, HTTPException, Query
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pwdlib import PasswordHash

from database import (
    conexion,
    inicializar_bd,
    migrar_cedula,
    migrar_multicuenta,
    crear_cuenta_bd,
)

from schemas import (
    Login,
    Operacion,
    PagoServicio,
    Registro,
    Transferencia,
    CompletarCedula,
    NuevaCuenta,
)


password_hasher = PasswordHash.recommended()
bearer = HTTPBearer(auto_error=False)

# Se usa para comprobar una contraseña incluso si el usuario no existe.
HASH_FICTICIO = password_hasher.hash("clave-interna-no-utilizada")


@asynccontextmanager
async def lifespan(app: FastAPI):
    inicializar_bd()
    migrar_cedula()
    migrar_multicuenta()
    yield


app = FastAPI(
    title="API Billetera Distribuida",
    version="0.2.0",
    lifespan=lifespan,
)


@app.exception_handler(sqlite3.OperationalError)
async def error_operacional(request, exc):
    codigo = getattr(exc, "sqlite_errorcode", 0) or 0

    if codigo & 0xFF in (sqlite3.SQLITE_BUSY, sqlite3.SQLITE_LOCKED):
        return JSONResponse(
            status_code=503,
            content={
                "detail": (
                    "Base de datos ocupada. Reintenta con la misma solicitud_id."
                )
            },
        )

    return JSONResponse(
        status_code=500,
        content={"detail": "Error interno de base de datos."},
    )


def hash_token(token: str):
    return hashlib.sha256(token.encode()).hexdigest()


def obtener_sesion(
    credenciales: HTTPAuthorizationCredentials | None = Depends(bearer),
):
    if credenciales is None:
        raise HTTPException(
            status_code=401,
            detail="Debes iniciar sesión.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    token_hash = hash_token(credenciales.credentials)

    with conexion() as db:
        fila = db.execute(
            """
            SELECT u.id AS usuario_id,
                   u.nombre,
                   u.usuario,
                   c.id AS cuenta_id,
                   s.expira AS expira
            FROM sesiones s
            JOIN usuarios u ON u.id = s.usuario_id
            JOIN cuentas c ON c.usuario_id = u.id
            WHERE s.token_hash = ? AND s.expira > ?
            ORDER BY c.id ASC
            LIMIT 1
            """,
            (token_hash, int(time.time())),
        ).fetchone()

    if fila is None:
        raise HTTPException(
            status_code=401,
            detail="Sesión inválida o expirada.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return {**dict(fila), "token_hash": token_hash}


@app.get("/inicio", tags=["Estado"])
def inicio():
    return {"estado": "activo", "version": "0.2.0"}


@app.post("/auth/registro", status_code=201, tags=["Autenticación"])
def registrar(datos: Registro):
    password_hash = password_hasher.hash(datos.password)

    try:
        with conexion(escritura=True) as db:

            cursor = db.execute(
                """
                INSERT INTO usuarios (
                    nombre, usuario, password_hash, cedula
                )
                VALUES (?, ?, ?, ?)
                """,
                (
                    datos.nombre,
                    datos.usuario,
                    password_hash,
                    datos.cedula,
                ),
            )

            cursor = db.execute(
                "INSERT INTO cuentas (usuario_id) VALUES (?)",
                (usuario_id,),
            )

            cuenta_id = cursor.lastrowid

    except sqlite3.IntegrityError:
        raise HTTPException(
            status_code=409,
            detail="El nombre de usuario o la cédula ya están registrados.",
        )

    return {
        "mensaje": "Usuario registrado.",
        "cuenta_id": cuenta_id,
        "numero_cuenta": cuenta["numero_cuenta"],
        "tipo": cuenta["tipo"],
        "alias": cuenta["alias"],
        "saldo_centavos": 0,
    }


@app.post("/auth/login", tags=["Autenticación"])
def login(datos: Login):
    with conexion() as db:
        usuario = db.execute(
            "SELECT * FROM usuarios WHERE usuario = ?",
            (datos.usuario,),
        ).fetchone()

    hash_guardado = (
        usuario["password_hash"] if usuario else HASH_FICTICIO
    )

    password_correcto = password_hasher.verify(
        datos.password,
        hash_guardado,
    )

    if usuario is None or not password_correcto:
        raise HTTPException(
            status_code=401,
            detail="Usuario o contraseña incorrectos.",
        )

    token = secrets.token_urlsafe(32)
    expira = int(time.time()) + 8 * 60 * 60

    with conexion(escritura=True) as db:
        db.execute(
            "DELETE FROM sesiones WHERE expira <= ?",
            (int(time.time()),),
        )
        db.execute(
            """
            INSERT INTO sesiones (token_hash, usuario_id, expira)
            VALUES (?, ?, ?)
            """,
            (hash_token(token), usuario["id"], expira),
        )

    return {
        "access_token": token,
        "token_type": "bearer",
        "expires_at": expira,
    }

def eliminar_sesion_bd(token_hash: str):
    with conexion(escritura=True) as db:
        db.execute(
            "DELETE FROM sesiones WHERE token_hash = ?",
            (token_hash,),
        )


@app.post("/auth/logout", tags=["Autenticación"])
async def logout(sesion=Depends(obtener_sesion)):
    await run_in_threadpool(
        eliminar_sesion_bd,
        sesion["token_hash"],
    )

    await gestor.cerrar_sesion(sesion["token_hash"])

    return {"mensaje": "Sesión cerrada."}


@app.get("/cuentas/me", tags=["Cuentas"])
def mi_cuenta(sesion=Depends(obtener_sesion)):
    with conexion() as db:
        cuenta = db.execute(
            "SELECT id, saldo_centavos FROM cuentas WHERE id = ?",
            (sesion["cuenta_id"],),
        ).fetchone()

    return {
        "cuenta_id": cuenta["id"],
        "nombre": sesion["nombre"],
        "usuario": sesion["usuario"],
        "saldo_centavos": cuenta["saldo_centavos"],
    }


@app.get("/movimientos", tags=["Movimientos"])
def historial(
    despues_de: int = Query(default=0, ge=0),
    limite: int = Query(default=100, ge=1, le=500),
    sesion=Depends(obtener_sesion),
):
    with conexion() as db:
        filas = db.execute(
            """
            SELECT *
            FROM movimientos
            WHERE cuenta_id = ? AND id > ?
            ORDER BY id ASC
            LIMIT ?
            """,
            (sesion["cuenta_id"], despues_de, limite),
        ).fetchall()

    return [dict(fila) for fila in filas]


def ejecutar_operacion(tipo: str, datos: Operacion, sesion: dict):
    cuenta_id = sesion["cuenta_id"]
    monto = datos.monto_centavos
    solicitud_id = str(datos.solicitud_id)

    contenido = json.dumps(
        {"tipo": tipo, **datos.model_dump(mode="json")},
        sort_keys=True,
    )

    with conexion(escritura=True) as db:
        anterior = db.execute(
            """
            SELECT datos, respuesta
            FROM operaciones
            WHERE cuenta_id = ? AND solicitud_id = ?
            """,
            (cuenta_id, solicitud_id),
        ).fetchone()

        if anterior:
            if anterior["datos"] != contenido:
                raise HTTPException(
                    status_code=409,
                    detail="La solicitud_id ya se usó con otros datos.",
                )

            return json.loads(anterior["respuesta"])

        destino_id = None

        if tipo == "TRANSFERENCIA":
            destino_id = datos.cuenta_destino_id

            if destino_id == cuenta_id:
                raise HTTPException(
                    status_code=400,
                    detail="No puedes transferir a tu misma cuenta.",
                )

            destino = db.execute(
                "SELECT id FROM cuentas WHERE id = ?",
                (destino_id,),
            ).fetchone()

            if destino is None:
                raise HTTPException(
                    status_code=404,
                    detail="La cuenta de destino no existe.",
                )

        if tipo == "DEPOSITO":
            db.execute(
                """
                UPDATE cuentas
                SET saldo_centavos = saldo_centavos + ?
                WHERE id = ?
                """,
                (monto, cuenta_id),
            )
        else:
            cursor = db.execute(
                """
                UPDATE cuentas
                SET saldo_centavos = saldo_centavos - ?
                WHERE id = ? AND saldo_centavos >= ?
                """,
                (monto, cuenta_id, monto),
            )

            if cursor.rowcount != 1:
                raise HTTPException(
                    status_code=409,
                    detail="Saldo insuficiente.",
                )

        if destino_id is not None:
            db.execute(
                """
                UPDATE cuentas
                SET saldo_centavos = saldo_centavos + ?
                WHERE id = ?
                """,
                (monto, destino_id),
            )

        saldo = db.execute(
            "SELECT saldo_centavos FROM cuentas WHERE id = ?",
            (cuenta_id,),
        ).fetchone()["saldo_centavos"]

        operacion_id = str(uuid4())

        respuesta = {
            "operacion_id": operacion_id,
            "solicitud_id": solicitud_id,
            "tipo": tipo,
            "monto_centavos": monto,
            "saldo_centavos": saldo,
            "mensaje": "Operación confirmada.",
        }

        db.execute(
            """
            INSERT INTO operaciones
                (id, cuenta_id, solicitud_id, datos, respuesta)
            VALUES (?, ?, ?, ?, ?)
            """,
            (
                operacion_id,
                cuenta_id,
                solicitud_id,
                contenido,
                json.dumps(respuesta),
            ),
        )

        if tipo == "TRANSFERENCIA":
            tipo_movimiento = "TRANSFERENCIA_ENVIADA"
            detalle = f"Transferencia a cuenta {destino_id}"
        elif tipo == "PAGO_SERVICIO":
            tipo_movimiento = tipo
            detalle = f"{datos.servicio}: {datos.referencia}"
        else:
            tipo_movimiento = tipo
            detalle = (
                "Depósito simulado"
                if tipo == "DEPOSITO"
                else "Retiro simulado"
            )

        db.execute(
            """
            INSERT INTO movimientos (
                operacion_id, cuenta_id, tipo, monto_centavos,
                saldo_resultante_centavos, detalle
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (
                operacion_id,
                cuenta_id,
                tipo_movimiento,
                monto,
                saldo,
                detalle,
            ),
        )

        if destino_id is not None:
            saldo_destino = db.execute(
                "SELECT saldo_centavos FROM cuentas WHERE id = ?",
                (destino_id,),
            ).fetchone()["saldo_centavos"]

            db.execute(
                """
                INSERT INTO movimientos (
                    operacion_id, cuenta_id, tipo, monto_centavos,
                    saldo_resultante_centavos, detalle
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    operacion_id,
                    destino_id,
                    "TRANSFERENCIA_RECIBIDA",
                    monto,
                    saldo_destino,
                    f"Transferencia desde cuenta {cuenta_id}",
                ),
            )

    return respuesta

async def operar_y_notificar(tipo, datos, sesion):
    respuesta = await run_in_threadpool(
        ejecutar_operacion,
        tipo,
        datos,
        sesion,
    )

    # ejecutar_operacion ya terminó y confirmó su transacción.
    cuentas = {sesion["cuenta_id"]}

    if tipo == "TRANSFERENCIA":
        cuentas.add(datos.cuenta_destino_id)

    await gestor.notificar(
        cuentas,
        respuesta["operacion_id"],
    )

    return respuesta


@app.post("/operaciones/deposito", tags=["Operaciones"])
async def deposito(
    datos: Operacion,
    sesion=Depends(obtener_sesion),
):
    return await operar_y_notificar("DEPOSITO", datos, sesion)


@app.post("/operaciones/retiro", tags=["Operaciones"])
async def retiro(
    datos: Operacion,
    sesion=Depends(obtener_sesion),
):
    return await operar_y_notificar("RETIRO", datos, sesion)


@app.post("/operaciones/transferencia", tags=["Operaciones"])
async def transferencia(
    datos: Transferencia,
    sesion=Depends(obtener_sesion),
):
    return await operar_y_notificar("TRANSFERENCIA", datos, sesion)


@app.post("/operaciones/pago-servicio", tags=["Operaciones"])
async def pago_servicio(
    datos: PagoServicio,
    sesion=Depends(obtener_sesion),
):
    return await operar_y_notificar("PAGO_SERVICIO", datos, sesion)

@app.websocket("/ws")
async def websocket_cuenta(websocket: WebSocket):
    # Android y Postman enviarán el token en el encabezado.
    autorizacion = websocket.headers.get("authorization", "")
    partes = autorizacion.split()

    if len(partes) != 2 or partes[0].lower() != "bearer":
        await websocket.close(code=1008)
        return

    credenciales = HTTPAuthorizationCredentials(
        scheme="Bearer",
        credentials=partes[1],
    )

    try:
        sesion = await run_in_threadpool(
            obtener_sesion,
            credenciales,
        )
    except HTTPException:
        await websocket.close(code=1008)
        return

    await websocket.accept()
    gestor.registrar(websocket, sesion)

    try:
        await gestor.enviar(
            websocket,
            {
                "evento": "conectado",
                "cuenta_id": sesion["cuenta_id"],
            },
        )

        while True:
            try:
                mensaje = await asyncio.wait_for(
                    websocket.receive_text(),
                    timeout=30,
                )
            except asyncio.TimeoutError:
                mensaje = None

            # Revalida periódicamente la sesión y su vencimiento.
            await run_in_threadpool(obtener_sesion, credenciales)

            if mensaje == "ping":
                await gestor.enviar(
                    websocket,
                    {"evento": "pong"},
                )

    except HTTPException:
        await gestor.cerrar(websocket)

    except WebSocketDisconnect:
        pass

    finally:
        gestor.eliminar(websocket)

@app.get("/usuarios/me", tags=["Usuarios"])
def mi_perfil(sesion=Depends(obtener_sesion)):
    with conexion() as db:
        usuario = db.execute(
            """
            SELECT id, nombre, usuario, cedula
            FROM usuarios
            WHERE id = ?
            """,
            (sesion["usuario_id"],),
        ).fetchone()

    if usuario is None:
        raise HTTPException(
            status_code=404,
            detail="Usuario no encontrado.",
        )

    return dict(usuario)


@app.patch("/usuarios/me/cedula", tags=["Usuarios"])
def completar_cedula(
    datos: CompletarCedula,
    sesion=Depends(obtener_sesion),
):
    try:
        with conexion(escritura=True) as db:
            usuario = db.execute(
                "SELECT cedula FROM usuarios WHERE id = ?",
                (sesion["usuario_id"],),
            ).fetchone()

            if usuario is None:
                raise HTTPException(
                    status_code=404,
                    detail="Usuario no encontrado.",
                )

            if (
                usuario["cedula"] is not None
                and usuario["cedula"] != datos.cedula
            ):
                raise HTTPException(
                    status_code=409,
                    detail="El usuario ya tiene una cédula asignada.",
                )

            db.execute(
                "UPDATE usuarios SET cedula = ? WHERE id = ?",
                (datos.cedula, sesion["usuario_id"]),
            )

    except sqlite3.IntegrityError:
        raise HTTPException(
            status_code=409,
            detail="La cédula ya pertenece a otro usuario.",
        )

    return {
        "mensaje": "Cédula registrada.",
        "cedula": datos.cedula,
    }

@app.get("/cuentas", tags=["Cuentas"])
def mis_cuentas(sesion=Depends(obtener_sesion)):
    with conexion() as db:
        filas = db.execute(
            """
            SELECT id AS cuenta_id, numero_cuenta,
                   tipo, alias, saldo_centavos
            FROM cuentas
            WHERE usuario_id = ?
            ORDER BY id
            """,
            (sesion["usuario_id"],),
        ).fetchall()

    return [dict(fila) for fila in filas]


@app.post("/cuentas", status_code=201, tags=["Cuentas"])
def abrir_cuenta(
    datos: NuevaCuenta,
    sesion=Depends(obtener_sesion),
):
    with conexion(escritura=True) as db:
        usuario = db.execute(
            "SELECT cedula FROM usuarios WHERE id = ?",
            (sesion["usuario_id"],),
        ).fetchone()

        if usuario is None or usuario["cedula"] is None:
            raise HTTPException(
                status_code=409,
                detail="Completa tu cédula antes de abrir otra cuenta.",
            )

        cuenta = crear_cuenta_bd(
            db,
            sesion["usuario_id"],
            datos.tipo,
            datos.alias,
        )

    return cuenta


@app.get("/cuentas/{cuenta_id}/movimientos", tags=["Movimientos"])
def historial_por_cuenta(
    cuenta_id: int,
    despues_de: int = Query(default=0, ge=0),
    limite: int = Query(default=100, ge=1, le=500),
    sesion=Depends(obtener_sesion),
):
    with conexion() as db:
        cuenta = db.execute(
            """
            SELECT id FROM cuentas
            WHERE id = ? AND usuario_id = ?
            """,
            (cuenta_id, sesion["usuario_id"]),
        ).fetchone()

        if cuenta is None:
            raise HTTPException(
                status_code=404,
                detail="Cuenta no encontrada.",
            )

        filas = db.execute(
            """
            SELECT * FROM movimientos
            WHERE cuenta_id = ? AND id > ?
            ORDER BY id ASC
            LIMIT ?
            """,
            (cuenta_id, despues_de, limite),
        ).fetchall()

    return [dict(fila) for fila in filas]