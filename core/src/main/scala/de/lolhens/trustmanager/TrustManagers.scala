package de.lolhens.trustmanager

import cats.kernel.Semigroup
import cats.syntax.all._
import org.log4s.getLogger

import java.io.{ByteArrayInputStream, IOException}
import java.nio.file.{Files, Path}
import java.security.KeyStore
import java.security.cert.{Certificate, CertificateException, CertificateFactory, X509Certificate}
import javax.net.ssl.{SSLContext, TrustManager, TrustManagerFactory, X509TrustManager}
import scala.collection.JavaConverters._

object TrustManagers {
  val insecureTrustManager: X509TrustManager = new X509TrustManager() {
    override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()

    override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()

    override def getAcceptedIssuers: Array[X509Certificate] = null
  }

  implicit val trustManagerSemigroup: Semigroup[X509TrustManager] =
    Semigroup.instance { (trustManager1, trustManager2) =>
      new X509TrustManager {
        override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit =
          try trustManager1.checkClientTrusted(chain, authType)
          catch {
            case certificateException1: CertificateException =>
              try trustManager2.checkClientTrusted(chain, authType)
              catch {
                case certificateException2: CertificateException =>
                  certificateException2.addSuppressed(certificateException1)
                  throw certificateException2
              }
          }

        override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit =
          try trustManager1.checkServerTrusted(chain, authType)
          catch {
            case certificateException1: CertificateException =>
              try trustManager2.checkServerTrusted(chain, authType)
              catch {
                case certificateException2: CertificateException =>
                  certificateException2.addSuppressed(certificateException1)
                  throw certificateException2
              }
          }

        override def getAcceptedIssuers: Array[X509Certificate] =
          trustManager1.getAcceptedIssuers ++ trustManager2.getAcceptedIssuers
      }
    }

  def trustManagerFromKeyStore(keyStore: KeyStore): X509TrustManager = {
    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(keyStore)
    for (trustManager <- trustManagerFactory.getTrustManagers) trustManager match {
      case x509TrustManager: X509TrustManager => return x509TrustManager
    }
    null
  }

  def defaultTrustManager: X509TrustManager = trustManagerFromKeyStore(null)

  def setDefaultTrustManager(trustManager: TrustManager): Unit = {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, Array(trustManager), null)
    SSLContext.setDefault(sslContext)
  }

  def keyStoreFromCertificates(certificates: Seq[Certificate]): KeyStore = {
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    keyStore.load(null, Array[Char]())
    certificates.zipWithIndex.foreach {
      case (certificate, i) =>
        keyStore.setCertificateEntry(i.toString, certificate)
    }
    keyStore
  }

  def x509CertificateFromBytes(bytes: Array[Byte]): Either[CertificateException, X509Certificate] =
    Either.catchOnly[CertificateException] {
      CertificateFactory.getInstance("X509")
        .generateCertificate(new ByteArrayInputStream(bytes))
        .asInstanceOf[X509Certificate]
    }

  private val logger = getLogger

  def trustManagerFromCertificatePath(path: Path): X509TrustManager =
    trustManagerFromKeyStore(keyStoreFromCertificates(
      Files.list(path).iterator.asScala
        .filter(Files.isRegularFile(_))
        .flatMap { file =>
          val certificateOrError = for {
            bytes <- Either.catchOnly[IOException](Files.readAllBytes(file))
            certificate <- x509CertificateFromBytes(bytes)
          } yield certificate
          certificateOrError.fold[Unit](
            logger.warn(_)(s"Failed to load certificate: $file"),
            _ => logger.info(s"Loaded certificate: $file")
          )
          certificateOrError.toSeq
        }.toSeq
    ))
}
