package com.franco.billeteradistribuida.datos

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Utilidades criptográficas para autenticación offline segura.
 * Nunca almacena contraseñas en texto plano: genera un salt aleatorio
 * y calcula SHA-256 para validación local cuando no hay conexión a internet.
 */
object SeguridadLocal {

    fun generarSalt(): String {
        val random = SecureRandom()
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun calcularHash(password: String, salt: String): String {
        val bytes = (salt + password).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun verificarPassword(password: String, salt: String, hashEsperado: String): Boolean {
        val hashCalculado = calcularHash(password, salt)
        return hashCalculado.equals(hashEsperado, ignoreCase = true)
    }
}

