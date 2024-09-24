package de.bitmarck.bms.zpc.datahub.util

import de.bitmarck.bms.zpc.datahub.util.TrustManagerInterceptorFactory._

import java.security.{KeyStore, Provider, Security}
import java.util
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.{ManagerFactoryParameters, TrustManager, TrustManagerFactory, TrustManagerFactorySpi}

class TrustManagerInterceptorFactory extends TrustManagerFactorySpi {
  override def engineInit(ks: KeyStore): Unit =
    delegate.init(ks)

  override def engineInit(spec: ManagerFactoryParameters): Unit =
    delegate.init(spec)

  override def engineGetTrustManagers(): Array[TrustManager] =
    trustManagersInterceptor.get().apply(delegate.getTrustManagers)
}

object TrustManagerInterceptorFactory {
  lazy val delegate: TrustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)

  private val trustManagersInterceptor: AtomicReference[Array[TrustManager] => Array[TrustManager]] = new AtomicReference(e => e)

  def setTrustManagersInterceptor(f: Array[TrustManager] => Array[TrustManager]): Unit =
    trustManagersInterceptor.set(f)

  object TrustManagerInterceptorFactoryProvider extends Provider("TrustManagerInterceptorFactory", "1.0", "") {
    private val _ = delegate

    putService(new Provider.Service(
      this,
      "TrustManagerFactory",
      "PKIX",
      classOf[TrustManagerInterceptorFactory].getName,
      util.List.of("SunPKIX", "X509", "X.509"),
      null
    ))
  }

  private lazy val insertProvider: Unit = Security.insertProviderAt(TrustManagerInterceptorFactoryProvider, 1)

  def registerProvider(): Unit = insertProvider
}
