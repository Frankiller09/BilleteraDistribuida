import hashlib
import json
import secrets
import time
from uuid import uuid4

from datetime import date
from fastapi import Depends, HTTPException, Query
from database import conexion

MAX_SALDO = 10_000_000_000  # Centavos; límite de la demostración.


def huella(texto):
    return hashlib.sha256(texto.encode()).hexdigest()


def exigir_sesion(db, sesion):
    fila = db.execute(
        'SELECT 1 FROM sesiones WHERE token_hash=? AND usuario_id=? AND expira>?',
        (sesion['token_hash'], sesion['usuario_id'], int(time.time())),
    ).fetchone()
    if fila is None:
        raise HTTPException(401, 'Sesión inválida o expirada.')


def cuenta_propia(db, cuenta_id, usuario_id):
    fila = db.execute(
        'SELECT * FROM cuentas WHERE id=? AND usuario_id=?',
        (cuenta_id, usuario_id),
    ).fetchone()
    if fila is None:
        raise HTTPException(404, 'Cuenta no encontrada.')
    return fila


def validar_destinatario_bd(datos, sesion):
    if (datos.tipo == 'TRANSFERENCIA') != (datos.cuenta_origen_id is not None):
        raise HTTPException(422, 'Solo las transferencias requieren cuenta_origen_id.')

    with conexion(escritura=True) as db:
        exigir_sesion(db, sesion)
        if datos.cuenta_origen_id is not None:
            cuenta_propia(db, datos.cuenta_origen_id, sesion['usuario_id'])

        destino = db.execute('''
            SELECT c.id, c.numero_cuenta, u.nombre
            FROM cuentas c JOIN usuarios u ON u.id=c.usuario_id
            WHERE c.numero_cuenta=?
        ''', (datos.numero_cuenta,)).fetchone()
        if destino is None:
            raise HTTPException(404, 'Cuenta destinataria no encontrada.')
        if destino['id'] == datos.cuenta_origen_id:
            raise HTTPException(400, 'Selecciona una cuenta de destino diferente.')

        ahora = int(time.time())
        recientes = db.execute('''
            SELECT COUNT(*) FROM confirmaciones
            WHERE usuario_id=? AND expira>?
        ''', (sesion['usuario_id'], ahora)).fetchone()[0]
        if recientes >= 30:
            raise HTTPException(429, 'Demasiadas consultas. Espera cinco minutos.')
        db.execute('DELETE FROM confirmaciones WHERE expira<?', (ahora - 86400,))

        token = secrets.token_urlsafe(32)
        expira = ahora + 300
        db.execute('''
            INSERT INTO confirmaciones (
                token_hash, sesion_hash, usuario_id, tipo, origen_id,
                destino_id, numero_cuenta, titular, monto_centavos, expira
            ) VALUES (?,?,?,?,?,?,?,?,?,?)
        ''', (huella(token), sesion['token_hash'], sesion['usuario_id'],
              datos.tipo, datos.cuenta_origen_id, destino['id'],
              destino['numero_cuenta'], destino['nombre'],
              datos.monto_centavos, expira))

    return {
        'confirmacion_token': token, 'expira': expira, 'tipo': datos.tipo,
        'cuenta_origen_id': datos.cuenta_origen_id,
        'numero_cuenta': destino['numero_cuenta'], 'titular': destino['nombre'],
        'monto_centavos': datos.monto_centavos, 'moneda': 'USD',
    }


def ejecutar_operacion(tipo, datos, sesion):
    payload = datos.model_dump(mode='json', exclude_none=True)
    if 'confirmacion_token' in payload:
        payload['confirmacion_token'] = huella(payload['confirmacion_token'])
    contenido = json.dumps({'tipo': tipo, **payload}, sort_keys=True)
    solicitud_id = str(datos.solicitud_id)

    with conexion(escritura=True) as db:
        exigir_sesion(db, sesion)
        anterior = db.execute('''
            SELECT datos, respuesta FROM operaciones
            WHERE actor_id=? AND solicitud_id=?
        ''', (sesion['usuario_id'], solicitud_id)).fetchone()
        if anterior:
            if anterior['datos'] != contenido:
                raise HTTPException(409, 'solicitud_id ya utilizada con otros datos.')
            return json.loads(anterior['respuesta']), set()

        origen = destino = confirmacion = None
        if tipo in ('DEPOSITO', 'TRANSFERENCIA'):
            confirmacion = db.execute(
                'SELECT * FROM confirmaciones WHERE token_hash=?',
                (payload['confirmacion_token'],),
            ).fetchone()
            if (confirmacion is None
                    or confirmacion['usuario_id'] != sesion['usuario_id']
                    or confirmacion['sesion_hash'] != sesion['token_hash']
                    or confirmacion['tipo'] != tipo
                    or confirmacion['usada']
                    or confirmacion['expira'] <= time.time()):
                raise HTTPException(409, 'Validación vencida, utilizada o inválida. Valida nuevamente.')
            destino = db.execute('''
                SELECT c.*, u.nombre FROM cuentas c
                JOIN usuarios u ON u.id=c.usuario_id WHERE c.id=?
            ''', (confirmacion['destino_id'],)).fetchone()
            if (destino is None or destino['nombre'] != confirmacion['titular']
                    or destino['numero_cuenta'] != confirmacion['numero_cuenta']):
                raise HTTPException(409, 'El destinatario cambió. Valida nuevamente.')
            monto = confirmacion['monto_centavos']
            if tipo == 'TRANSFERENCIA':
                origen = cuenta_propia(db, confirmacion['origen_id'], sesion['usuario_id'])
        else:
            origen = cuenta_propia(
                db, datos.cuenta_origen_id or sesion['cuenta_id'], sesion['usuario_id'],
            )
            monto = datos.monto_centavos

        principal = origen if origen is not None else destino
        # Evita reutilizar un identificador anterior a esta actualización.
        if db.execute('SELECT 1 FROM operaciones WHERE cuenta_id=? AND solicitud_id=?',
                      (principal['id'], solicitud_id)).fetchone():
            raise HTTPException(409, 'Identificador ya registrado en esta cuenta. Revisa su historial.')

        if origen is not None:
            cambio = db.execute('''
                UPDATE cuentas SET saldo_centavos=saldo_centavos-?
                WHERE id=? AND saldo_centavos>=?
            ''', (monto, origen['id'], monto))
            if cambio.rowcount != 1:
                raise HTTPException(409, 'Saldo insuficiente.')
        if destino is not None:
            cambio = db.execute('''
                UPDATE cuentas SET saldo_centavos=saldo_centavos+?
                WHERE id=? AND saldo_centavos<=?
            ''', (monto, destino['id'], MAX_SALDO - monto))
            if cambio.rowcount != 1:
                raise HTTPException(409, 'El abono supera el límite de saldo.')

        operacion_id = str(uuid4())
        respuesta = {
            'operacion_id': operacion_id, 'solicitud_id': solicitud_id,
            'tipo': tipo, 'monto_centavos': monto, 'moneda': 'USD',
            'mensaje': 'Operación confirmada.', 'simulado': True,
        }
        if principal['usuario_id'] == sesion['usuario_id']:
            respuesta['saldo_centavos'] = db.execute(
                'SELECT saldo_centavos FROM cuentas WHERE id=?', (principal['id'],),
            ).fetchone()[0]
        if origen is not None:
            respuesta['numero_origen'] = origen['numero_cuenta']
        if destino is not None:
            respuesta.update(numero_destino=destino['numero_cuenta'], titular_destino=destino['nombre'])
        if tipo == 'PAGO_SERVICIO':
            respuesta.update(servicio=datos.servicio, referencia=datos.referencia)

        db.execute('''
            INSERT INTO operaciones(id,cuenta_id,solicitud_id,datos,respuesta,actor_id)
            VALUES (?,?,?,?,?,?)
        ''', (operacion_id, principal['id'], solicitud_id, contenido,
              json.dumps(respuesta), sesion['usuario_id']))
        cuentas = set()

        def movimiento(cuenta, clase, detalle, meta):
            saldo = db.execute('SELECT saldo_centavos FROM cuentas WHERE id=?', (cuenta['id'],)).fetchone()[0]
            db.execute('''
                INSERT INTO movimientos(operacion_id,cuenta_id,tipo,monto_centavos,
                    saldo_resultante_centavos,detalle,metadatos)
                VALUES (?,?,?,?,?,?,?)
            ''', (operacion_id, cuenta['id'], clase, monto, saldo, detalle, json.dumps(meta)))
            cuentas.add(cuenta['id'])

        if tipo == 'TRANSFERENCIA':
            movimiento(origen, 'TRANSFERENCIA_ENVIADA', f"Transferencia a {destino['nombre']}",
                       {'numero_contraparte': destino['numero_cuenta'], 'titular': destino['nombre']})
            movimiento(destino, 'TRANSFERENCIA_RECIBIDA', f"Transferencia de {sesion['nombre']}",
                       {'numero_contraparte': origen['numero_cuenta'], 'titular': sesion['nombre']})
        elif tipo == 'DEPOSITO':
            movimiento(destino, tipo, 'Depósito simulado', {'depositante': sesion['nombre']})
        elif tipo == 'RETIRO':
            movimiento(origen, tipo, 'Retiro simulado', {})
        else:
            movimiento(origen, tipo, f'{datos.servicio}: {datos.referencia}',
                       {'servicio': datos.servicio, 'referencia': datos.referencia})
        if confirmacion is not None:
            db.execute('UPDATE confirmaciones SET usada=1 WHERE token_hash=?', (confirmacion['token_hash'],))

    return respuesta, cuentas


def registrar_consultas(app, obtener_sesion):
    def movimiento_publico(fila):
        resultado = dict(fila)
        resultado['metadatos'] = json.loads(resultado.get('metadatos', '{}'))
        resultado['direccion'] = (
            'INGRESO' if resultado['tipo'] in ('DEPOSITO', 'TRANSFERENCIA_RECIBIDA')
            else 'EGRESO'
        )
        resultado['moneda'] = 'USD'
        return resultado


    @app.get('/cuentas/{cuenta_id}', tags=['Cuentas'])
    def detalle_cuenta(cuenta_id: int, sesion=Depends(obtener_sesion)):
        with conexion() as db:
            cuenta = dict(cuenta_propia(db, cuenta_id, sesion['usuario_id']))
        cuenta['cuenta_id'] = cuenta.pop('id')
        cuenta.pop('usuario_id', None)
        return cuenta


    @app.get('/cuentas/{cuenta_id}/movimientos', tags=['Movimientos'])
    def historial_por_cuenta(
        cuenta_id: int,
        limite: int = Query(20, ge=1, le=100),
        antes_de: int | None = Query(None, ge=1),
        tipo: str | None = Query(None, max_length=40),
        desde: date | None = None,
        hasta: date | None = None,
        buscar: str | None = Query(None, max_length=80),
        sesion=Depends(obtener_sesion),
    ):
        if desde and hasta and desde > hasta:
            raise HTTPException(422, 'El rango de fechas es inválido.')
        condiciones, parametros = ['cuenta_id=?'], [cuenta_id]
        filtros = [
            (antes_de, 'id<?'), (tipo, 'tipo=?'),
            (str(desde) if desde else None, 'date(creado)>=?'),
            (str(hasta) if hasta else None, 'date(creado)<=?'),
            (f'%{buscar}%' if buscar else None, 'detalle LIKE ?'),
        ]
        for valor, condicion in filtros:
            if valor is not None:
                condiciones.append(condicion)
                parametros.append(valor)
        with conexion() as db:
            cuenta_propia(db, cuenta_id, sesion['usuario_id'])
            filas = db.execute(
                'SELECT * FROM movimientos WHERE ' + ' AND '.join(condiciones)
                + ' ORDER BY id DESC LIMIT ?', (*parametros, limite + 1),
            ).fetchall()
        items = [movimiento_publico(fila) for fila in filas[:limite]]
        return {
            'items': items,
            'siguiente_antes_de': items[-1]['id'] if len(filas) > limite else None,
        }


    @app.get('/movimientos/{movimiento_id}', tags=['Movimientos'])
    def comprobante(movimiento_id: int, sesion=Depends(obtener_sesion)):
        with conexion() as db:
            fila = db.execute('''
                SELECT m.*, c.numero_cuenta
                FROM movimientos m JOIN cuentas c ON c.id=m.cuenta_id
                WHERE m.id=? AND c.usuario_id=?
            ''', (movimiento_id, sesion['usuario_id'])).fetchone()
        if fila is None:
            raise HTTPException(404, 'Movimiento no encontrado.')
        return movimiento_publico(fila)


    @app.get('/cuentas/{cuenta_id}/resumen', tags=['Movimientos'])
    def resumen_cuenta(cuenta_id: int, desde: date, hasta: date, sesion=Depends(obtener_sesion)):
        if desde > hasta or (hasta - desde).days > 366:
            raise HTTPException(422, 'Selecciona un rango de hasta 366 días.')
        with conexion() as db:
            db.execute('BEGIN')
            cuenta = cuenta_propia(db, cuenta_id, sesion['usuario_id'])
            filas = db.execute('''
                SELECT date(creado) AS fecha, tipo,
                       SUM(monto_centavos) AS total, COUNT(*) AS cantidad
                FROM movimientos
                WHERE cuenta_id=? AND date(creado) BETWEEN ? AND ?
                GROUP BY date(creado), tipo ORDER BY fecha, tipo
            ''', (cuenta_id, str(desde), str(hasta))).fetchall()
        ingresos = egresos = cantidad = 0
        dias, categorias = {}, {}
        for fila in filas:
            entrada = fila['tipo'] in ('DEPOSITO', 'TRANSFERENCIA_RECIBIDA')
            total = fila['total']
            ingresos += total if entrada else 0
            egresos += 0 if entrada else total
            cantidad += fila['cantidad']
            dia = dias.setdefault(fila['fecha'], {
                'fecha': fila['fecha'], 'ingresos_centavos': 0, 'egresos_centavos': 0,
            })
            dia['ingresos_centavos' if entrada else 'egresos_centavos'] += total
            categorias[fila['tipo']] = categorias.get(fila['tipo'], 0) + total
        return {
            'cuenta_id': cuenta_id, 'desde': desde, 'hasta': hasta,
            'saldo_actual_centavos': cuenta['saldo_centavos'],
            'ingresos_centavos': ingresos, 'egresos_centavos': egresos,
            'neto_centavos': ingresos - egresos, 'cantidad_movimientos': cantidad,
            'por_dia': list(dias.values()), 'por_tipo': categorias, 'moneda': 'USD',
        }


    @app.get('/servicios', tags=['Servicios'])
    def catalogo_servicios(sesion=Depends(obtener_sesion)):
        return {
            'simulado': True,
            'servicios': [
                {'codigo': 'LUZ', 'nombre': 'Luz', 'referencia': 'Número de suministro'},
                {'codigo': 'AGUA', 'nombre': 'Agua', 'referencia': 'Número de contrato'},
                {'codigo': 'INTERNET', 'nombre': 'Internet', 'referencia': 'Número de contrato'},
                {'codigo': 'TELEFONIA', 'nombre': 'Plan telefónico', 'referencia': 'Número de línea o contrato'},
            ],
        }
