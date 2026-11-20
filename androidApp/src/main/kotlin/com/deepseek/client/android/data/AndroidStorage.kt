package com.deepseek.client.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.deepseek.client.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.io.FileNotFoundException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API Key bytes never enter SharedPreferences, state snapshots, backups, or diagnostic text. */
class AndroidSecureKeyStore(context: Context) : SecureKeyStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "api-key.enc"))
    private val alias = "deepseek.client.api-key.v1"
    private val lock = Any()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override suspend fun read(): String? = withContext(Dispatchers.IO) { synchronized(lock) {
        val data = try { file.openRead().use { it.readBytes() } } catch (_: FileNotFoundException) { return@synchronized null }
        require(data.size in 30..16384 && data[0] == 1.toByte())
        val size = data[1].toInt() and 0xff
        require(size == 12 && data.size > size + 18)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(2, size + 2)))
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        cipher.doFinal(data.copyOfRange(size + 2, data.size)).toString(Charsets.UTF_8)
    } }
    override suspend fun save(value: String) = withContext(Dispatchers.IO) { synchronized(lock) {
        require(value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size < 8192)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val output = byteArrayOf(1, cipher.iv.size.toByte()) + cipher.iv + encrypted
        val stream = file.startWrite()
        try { stream.write(output); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
    } }
    override suspend fun clear() = withContext(Dispatchers.IO) { synchronized(lock) {
        file.delete()
        check(!file.baseFile.exists())
    } }
}

class AndroidSnapshotStore(context: Context) : SnapshotStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "history-v1.json"))
    private val lock = Any()
    override suspend fun read(): String? = withContext(Dispatchers.IO) { synchronized(lock) {
        try {
            file.openRead().use { stream ->
                require(stream.channel.size() <= 64L * 1024 * 1024)
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (_: FileNotFoundException) { null }
    } }
    override suspend fun write(value: String) = withContext(Dispatchers.IO) { synchronized(lock) {
        val stream = file.startWrite()
        try { stream.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    } }
}
