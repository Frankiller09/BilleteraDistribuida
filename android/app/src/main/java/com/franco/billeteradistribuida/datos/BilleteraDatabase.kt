package com.franco.billeteradistribuida.datos

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MovimientoEntity::class,
        CuentaEntity::class,
        SesionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class BilleteraDatabase : RoomDatabase() {

    abstract fun movimientoDao(): MovimientoDao
    abstract fun cuentaDao(): CuentaDao
    abstract fun sesionDao(): SesionDao

    companion object {
        @Volatile
        private var INSTANCIA: BilleteraDatabase? = null

        fun obtenerInstancia(context: Context): BilleteraDatabase {
            return INSTANCIA ?: synchronized(this) {
                val instancia = Room.databaseBuilder(
                    context.applicationContext,
                    BilleteraDatabase::class.java,
                    "billetera_distribuida.db"
                ).build()
                INSTANCIA = instancia
                instancia
            }
        }
    }
}

