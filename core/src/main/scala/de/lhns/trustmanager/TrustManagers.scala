package de.lhns.trustmanager

import cats.kernel.Semigroup
import cats.syntax.all._
import org.log4s.getLogger

import java.io.{ByteArrayInputStream, FileInputStream, IOException}
import java.nio.file.{Files, Path, Paths}
import java.security.KeyStore
import java.security.cert.{Certificate, CertificateException, CertificateFactory, X509Certificate}
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.{SSLContext, TrustManagerFactory, X509TrustManager}
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
    trustManagerFactory.getTrustManagers.collectFirst {
      case x509TrustManager: X509TrustManager => x509TrustManager
    }.orNull
  }

  lazy val jreTrustManager: X509TrustManager = {
    lazy val trustStoreType = Option(System.getProperty("javax.net.ssl.trustStoreType")).getOrElse(KeyStore.getDefaultType)
    lazy val trustStoreProvider = Option(System.getProperty("javax.net.ssl.trustStoreProvider"))
    lazy val trustStore = Option(System.getProperty("javax.net.ssl.trustStore")).filterNot(_ == "NONE")
    lazy val trustStorePassword = Option(System.getProperty("javax.net.ssl.trustStorePassword")).map(_.toCharArray)

    val keyStore = trustStore.map { trustStorePath =>
      val keyStore = trustStoreProvider.fold(
        KeyStore.getInstance(trustStoreType)
      )(trustStoreProvider =>
        KeyStore.getInstance(trustStoreType, trustStoreProvider)
      )

      val inputStream = new FileInputStream(trustStorePath)
      try {
        keyStore.load(inputStream, trustStorePassword.orNull)
      } finally {
        inputStream.close()
      }

      keyStore
    }

    trustManagerFromKeyStore(keyStore.orNull)
  }

  private class ThreadSafeX509TrustManager(initial: X509TrustManager) extends X509TrustManager {
    private val atomicTrustManager = new AtomicReference(initial)

    def set(trustManager: X509TrustManager): Unit =
      atomicTrustManager.set(trustManager)

    def get(): X509TrustManager =
      atomicTrustManager.get()

    override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit =
      atomicTrustManager.get().checkClientTrusted(chain, authType)

    override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit =
      atomicTrustManager.get().checkServerTrusted(chain, authType)

    override def getAcceptedIssuers: Array[X509Certificate] =
      atomicTrustManager.get().getAcceptedIssuers
  }

  private lazy val defaultThreadSafeTrustManager = new ThreadSafeX509TrustManager(jreTrustManager)

  def defaultTrustManager: X509TrustManager =
    defaultThreadSafeTrustManager.get()

  def setDefaultTrustManager(trustManager: X509TrustManager): Unit = {
    defaultThreadSafeTrustManager.set(trustManager)
    SSLContext.setDefault(sslContext(defaultThreadSafeTrustManager))
  }

  private def sslContext(trustManager: X509TrustManager): SSLContext = {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, Array(trustManager), null)
    sslContext
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

  def keyStoreFromCertificatePath(path: Path, onLoad: Either[(Throwable, Path), Path] => Unit): KeyStore =
    keyStoreFromCertificates(
      (if (Files.isDirectory(path)) Files.list(path).iterator.asScala
      else Seq(path))
        .filter(path => !path.getFileName.toString.startsWith(".") && Files.isRegularFile(path))
        .flatMap { file =>
          val certificateOrError = for {
            bytes <- Either.catchOnly[IOException].apply(Files.readAllBytes(file))
            certificate <- x509CertificateFromBytes(bytes)
          } yield certificate
          certificateOrError.fold[Unit](
            error => onLoad(Left((error, file))),
            _ => onLoad(Right(file))
          )
          certificateOrError.toSeq
        }.toSeq
    )

  private lazy val logger = getLogger

  private def logOnLoad: Either[(Throwable, Path), Path] => Unit = {
    case Left((throwable, file)) =>
      logger.warn(throwable)(s"Failed to load certificate: $file")
    case Right(file) =>
      logger.info(s"Loaded certificate: $file")
  }

  def keyStoreFromCertificatePath(path: Path): KeyStore =
    keyStoreFromCertificatePath(path, onLoad = logOnLoad)

  def x509CertificateFromBytes(bytes: Array[Byte]): Either[CertificateException, X509Certificate] =
    Either.catchOnly[CertificateException].apply {
      CertificateFactory.getInstance("X509")
        .generateCertificate(new ByteArrayInputStream(bytes))
        .asInstanceOf[X509Certificate]
    }

  def trustManagerFromCertificatePath(path: Path, onLoad: Either[(Throwable, Path), Path] => Unit): X509TrustManager =
    trustManagerFromKeyStore(keyStoreFromCertificatePath(path, onLoad))

  def trustManagerFromCertificatePath(path: Path): X509TrustManager =
    trustManagerFromCertificatePath(path, onLoad = logOnLoad)

  lazy val trustManagerFromEnvVar: Option[X509TrustManager] =
    Option(System.getenv("https_cert_path"))
      .orElse(Option(System.getenv("https_certs_path")))
      .map { string =>
        val path = Paths.get(string)
        logger.debug(s"https_cert_path: $path")
        trustManagerFromCertificatePath(path, onLoad = logOnLoad)
      }

  lazy val jreTrustManagerWithEnvVar: X509TrustManager =
    Semigroup.maybeCombine(trustManagerFromEnvVar, jreTrustManager)

  lazy val insecureTrustManagerFromEnvVar: Option[X509TrustManager] =
    Option(System.getenv("https_insecure"))
      .filter(_.equalsIgnoreCase("true"))
      .map(_ => insecureTrustManager)

  lazy val jreTrustManagerInsecureWithEnvVar: X509TrustManager =
    Semigroup.maybeCombine(insecureTrustManagerFromEnvVar, jreTrustManagerWithEnvVar)
}
