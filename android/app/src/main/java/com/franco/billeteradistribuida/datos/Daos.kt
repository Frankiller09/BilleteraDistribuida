package com.franco.billeteradistribuida.datos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MovimientoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarMovimientos(movimientos: List<MovimientoEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarMovimiento(movimiento: MovimientoEntity): Long

    /**
     * Consulta reactiva que devuelve un Flow. Room emite automáticamente cuando
     * hay inserciones o modificaciones en la base de datos local SQLite.
     * Respeta filtros de tipo, búsqueda de texto y rango de fechas (UTC).
     */
    @Query("""
        SELECT * FROM movimientos
        WHERE cuentaId = :cuentaId
          AND usuario = :usuario
          AND (:tipo = '' OR tipo = :tipo)
          AND (:buscar = '' OR detalle LIKE '%' || :buscar || '%')
          AND (:desde = '' OR date(fecha) >= :desde)
          AND (:hasta = '' OR date(fecha) <= :hasta)
        ORDER BY id DESC
    """)
    fun observarMovimientos(
        cuentaId: Long,
        usuario: String,
        tipo: String,
        buscar: String,
        desde: String,
        hasta: String
    ): Flow<List<MovimientoEntity>>

    @Query("SELECT * FROM movimientos WHERE id = :id AND usuario = :usuario LIMIT 1")
    suspend fun obtenerPorId(id: Long, usuario: String): MovimientoEntity?

    @Query("SELECT COUNT(*) FROM movimientos WHERE cuentaId = :cuentaId AND usuario = :usuario")
    suspend fun contarMovimientos(cuentaId: Long, usuario: String): Int

    @Query("DELETE FROM movimientos WHERE usuario = :usuario")
    suspend fun borrarMovimientosDeUsuario(usuario: String): Int
}

@Dao
interface CuentaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarCuentas(cuentas: List<CuentaEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarCuenta(cuenta: CuentaEntity): Long

    @Query("SELECT * FROM cuentas WHERE usuario = :usuario ORDER BY id ASC")
    fun observarCuentas(usuario: String): Flow<List<CuentaEntity>>

    @Query("SELECT * FROM cuentas WHERE usuario = :usuario ORDER BY id ASC")
    suspend fun obtenerCuentas(usuario: String): List<CuentaEntity>

    @Query("UPDATE cuentas SET saldoCentavos = :nuevoSaldo, ultimaSincronizacion = :timestamp WHERE id = :cuentaId")
    suspend fun actualizarSaldo(cuentaId: Long, nuevoSaldo: Long, timestamp: Long): Int

    @Query("DELETE FROM cuentas WHERE usuario = :usuario")
    suspend fun borrarCuentasDeUsuario(usuario: String): Int
}

@Dao
interface SesionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarSesion(sesion: SesionEntity): Long

    @Query("SELECT * FROM sesiones_locales WHERE usuario = :usuario LIMIT 1")
    suspend fun obtenerPorUsuario(usuario: String): SesionEntity?

    @Query("SELECT * FROM sesiones_locales WHERE activa = 1 ORDER BY ultimaSincronizacion DESC LIMIT 1")
    suspend fun obtenerSesionActiva(): SesionEntity?

    @Query("UPDATE sesiones_locales SET activa = 0")
    suspend fun desactivarTodas(): Int

    @Query("DELETE FROM sesiones_locales WHERE usuario = :usuario")
    suspend fun eliminarSesion(usuario: String): Int
}
