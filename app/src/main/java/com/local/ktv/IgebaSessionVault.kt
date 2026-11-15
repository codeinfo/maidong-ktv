package com.local.ktv

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/**
 * 会话加密存储（移植自 2.1.12）：AES-GCM 数据 + AndroidKeyStore RSA 包裹密钥，
 * 仅在用户显式勾选「加密保存密码」时写入，且尽量放在不参与备份的私有目录。
 * 依赖的 KeyPairGeneratorSpec（API 18）、GCMParameterSpec（API 19）在 minSdk 19 上可用。
 *
 * 注意：Context.getNoBackupFilesDir() 是 API 21 才有的方法，2.1.12 因 minSdk 21 可以直接用；
 * 本分支 minSdk 19，在 4.4（API 19）上调用会抛 NoSuchMethodError，因此按版本降级到 filesDir。
 */
internal class IgebaSessionVault(
    context: Context,
    sessionFile: File = defaultSessionFile(context),
) {
    private val context = context.applicationContext
    private val file = AtomicFile(sessionFile)
    private val alias = "maidong.igeba.session"

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey() {
        if (keyStore().containsAlias(alias)) return
        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
        val spec = KeyPairGeneratorSpec.Builder(context).setAlias(alias)
            .setSubject(X500Principal("CN=Maidong Igeba Session"))
            .setSerialNumber(BigInteger.ONE).setStartDate(start.time).setEndDate(end.time)
            .setKeySize(2048).build()
        KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply { initialize(spec) }
            .generateKeyPair()
    }

    @Synchronized
    fun save(value: JSONObject) {
        ensureKey()
        val secret = KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret) }
        val sealed = cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        val wrapper = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
            init(Cipher.ENCRYPT_MODE, keyStore().getCertificate(alias).publicKey)
        }
        val record = JSONObject().put("version", 1).put("key", encode(wrapper.doFinal(secret.encoded)))
            .put("iv", encode(cipher.iv)).put("data", encode(sealed)).toString()
            .toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(record)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun load(): JSONObject? {
        if (!file.baseFile.exists()) return null
        val record = JSONObject(String(file.readFully(), Charsets.UTF_8))
        require(record.getInt("version") == 1)
        val wrapper = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
            init(Cipher.DECRYPT_MODE, keyStore().getKey(alias, null))
        }
        val key = SecretKeySpec(wrapper.doFinal(decode(record.getString("key"))), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode(record.getString("iv"))))
        }
        return JSONObject(
            String(cipher.doFinal(decode(record.getString("data"))), Charsets.UTF_8)
        )
    }

    @Synchronized
    fun clear() {
        file.delete()
    }
}

/**
 * API 21+ 使用不参与备份的私有目录；4.4（API 19）没有该方法，退回应用私有 filesDir。
 * 用 runCatching 兜住：Dalvik 上直接引用 API 21 的方法有解析风险，异常时静默降级。
 */
private fun defaultSessionFile(context: Context): File {
    val noBackup = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        runCatching { context.noBackupFilesDir }.getOrNull()
    } else {
        null
    }
    return File(noBackup ?: context.filesDir, "igeba-session.bin")
}
