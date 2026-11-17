package de.lhns.trustmanager

import munit.FunSuite

import java.nio.file.{Files, Path, Paths}

class TrustManagersSuite extends FunSuite {
  private def resource(name: String): Path =
    Paths.get(getClass.getResource(s"/$name").toURI)

  private def noLog: Either[(Throwable, Path), Path] => Unit = _ => ()

  test("keyStoreFromCertificatePath loads a single certificate") {
    assertEquals(TrustManagers.keyStoreFromCertificatePath(resource("single.pem"), noLog).size(), 1)
  }

  test("keyStoreFromCertificatePath loads every certificate of a bundle") {
    assertEquals(TrustManagers.keyStoreFromCertificatePath(resource("bundle.pem"), noLog).size(), 2)
  }

  test("trustManagerFromCertificatePath accepts every certificate of a bundle") {
    assertEquals(TrustManagers.trustManagerFromCertificatePath(resource("bundle.pem"), noLog).getAcceptedIssuers.length, 2)
  }

  test("x509CertificatesFromBytes returns all certificates") {
    val bytes = Files.readAllBytes(resource("bundle.pem"))
    assertEquals(TrustManagers.x509CertificatesFromBytes(bytes).map(_.size), Right(2))
  }

  test("x509CertificatesFromBytes rejects input without certificates") {
    assert(TrustManagers.x509CertificatesFromBytes(Array.emptyByteArray).isLeft)
    assert(TrustManagers.x509CertificatesFromBytes("garbage".getBytes).isLeft)
  }

  test("a file without certificates is reported as failed") {
    val dir = Files.createTempDirectory("certs")
    val empty = Files.write(dir.resolve("empty.pem"), Array.emptyByteArray)
    var results = List.empty[Either[(Throwable, Path), Path]]
    val keyStore = TrustManagers.keyStoreFromCertificatePath(dir, r => results ::= r)
    assertEquals(keyStore.size(), 0)
    assert(results.exists(_.left.exists(_._2 == empty)), results)
  }
}
