package expo.modules.densifynative

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import sun.security.x509.AlgorithmId
import sun.security.x509.CertificateAlgorithmId
import sun.security.x509.CertificateIssuerName
import sun.security.x509.CertificateSerialNumber
import sun.security.x509.CertificateSubjectName
import sun.security.x509.CertificateValidity
import sun.security.x509.CertificateVersion
import sun.security.x509.CertificateX509Key
import sun.security.x509.X500Name
import sun.security.x509.X509CertImpl
import sun.security.x509.X509CertInfo
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** RSA identity Densify presents to adbd. Pairing authorises it once. */
class DensifyAdbManager(ctx: Context) : AbsAdbConnectionManager() {
  private val keyFile = File(ctx.filesDir, "adb_key.pk8")
  private val certFile = File(ctx.filesDir, "adb_cert.der")
  private val pk: PrivateKey
  private val cert: Certificate

  init {
    setApi(Build.VERSION.SDK_INT)
    val loaded = runCatching {
      val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
      val c = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
      Pair(key, c)
    }.getOrNull()
    if (loaded != null) {
      pk = loaded.first
      cert = loaded.second
    } else {
      val gen = KeyPairGenerator.getInstance("RSA")
      gen.initialize(2048, SecureRandom())
      val pair = gen.generateKeyPair()
      val name = X500Name("CN=Densify")
      val info = X509CertInfo()
      info.set(X509CertInfo.VERSION, CertificateVersion(CertificateVersion.V3))
      info.set(X509CertInfo.SERIAL_NUMBER, CertificateSerialNumber(SecureRandom().nextInt() and Int.MAX_VALUE))
      info.set(X509CertInfo.ALGORITHM_ID, CertificateAlgorithmId(AlgorithmId.get("SHA512withRSA")))
      info.set(X509CertInfo.SUBJECT, CertificateSubjectName(name))
      info.set(X509CertInfo.ISSUER, CertificateIssuerName(name))
      info.set(X509CertInfo.VALIDITY, CertificateValidity(Date(), Date(System.currentTimeMillis() + 10L * 365 * 86_400_000)))
      info.set(X509CertInfo.KEY, CertificateX509Key(pair.public))
      val x509 = X509CertImpl(info)
      x509.sign(pair.private, "SHA512withRSA")
      keyFile.writeBytes(pair.private.encoded)
      certFile.writeBytes(x509.encoded)
      pk = pair.private
      cert = x509
    }
  }

  override fun getPrivateKey(): PrivateKey = pk
  override fun getCertificate(): Certificate = cert
  override fun getDeviceName(): String = "Densify"
}
