package com.franco.billeteradistribuida.datos

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.franco.billeteradistribuida.Cuenta
import com.franco.billeteradistribuida.Movimiento

/**
 * Entidad de Room para persistir movimientos y comprobantes.
 * Separados estrictamente por cuenta y por usuario para garantizar la privacidad.
 */
@Entity(
    tableName = "movimientos",
    indices = [
        Index(value = ["cuentaId", "usuario"]),
        Index(value = ["usuario"]),
        Index(value = ["fecha"])
    ]
)
data class MovimientoEntity(
    @PrimaryKey
    val id: Long,
    val cuentaId: Long,
    val usuario: String,
    val tipo: String,
    val monto: Long,
    val saldo: Long,
    val detalle: String,
    val fecha: String,
    val operacion: String,
    val ingreso: Boolean,
    val numero: String,
    val categoriaVisual: String,
    val guardadoTimestamp: Long
)

/**
 * Entidad de Room para almacenar cuentas por usuario.
 */
@Entity(
    tableName = "cuentas",
    indices = [
        Index(value = ["usuario"])
    ]
)
data class CuentaEntity(
    @PrimaryKey
    val id: Long,
    val usuario: String,
    val nombre: String,
    val saldoCentavos: Long,
    val numero: String,
    val tipo: String,
    val alias: String,
    val ultimaSincronizacion: Long
)

/**
 * Entidad de Room para recordar la última sesión del usuario de forma segura.
 * Solo guarda salt y hash SHA-256; NUNCA contraseñas en texto plano.
 */
@Entity(tableName = "sesiones_locales")
data class SesionEntity(
    @PrimaryKey
    val usuario: String,
    val nombre: String,
    val cedula: String?,
    val passwordHash: String,
    val salt: String,
    val ultimaSincronizacion: Long,
    val activa: Boolean
)

// Funciones de mapeo entre entidades de Room y modelos de dominio / UI
fun MovimientoEntity.aModelo(): Movimiento = Movimiento(
    id = id,
    tipo = tipo,
    monto = monto,
    saldo = saldo,
    detalle = detalle,
    fecha = fecha,
    operacion = operacion,
    ingreso = ingreso,
    numero = numero,
    categoriaVisual = categoriaVisual
)

fun Movimiento.aEntidad(cuentaId: Long, usuario: String): MovimientoEntity = MovimientoEntity(
    id = id,
    cuentaId = cuentaId,
    usuario = usuario,
    tipo = tipo,
    monto = monto,
    saldo = saldo,
    detalle = detalle,
    fecha = fecha,
    operacion = operacion,
    ingreso = ingreso,
    numero = numero,
    categoriaVisual = if (tipo == "PAGO_SERVICIO") "Servicio Básico" else tipo,
    guardadoTimestamp = System.currentTimeMillis()
)

fun CuentaEntity.aModelo(): Cuenta = Cuenta(
    id = id,
    nombre = nombre,
    usuario = usuario,
    saldoCentavos = saldoCentavos,
    numero = numero,
    tipo = tipo,
    alias = alias
)

fun Cuenta.aEntidad(usuario: String): CuentaEntity = CuentaEntity(
    id = id,
    usuario = usuario,
    nombre = nombre,
    saldoCentavos = saldoCentavos,
    numero = numero,
    tipo = tipo,
    alias = alias,
    ultimaSincronizacion = System.currentTimeMillis()
)

