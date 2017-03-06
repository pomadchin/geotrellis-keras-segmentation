package com.azavea

import akka.actor.Props
import akka.io.IO
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import org.apache.spark.{SparkConf, SparkContext}

object AkkaSystem {
  implicit val system = ActorSystem("keras-server")
  implicit val materializer = ActorMaterializer()

  trait LoggerExecutor {
    protected implicit val log = Logging(system, "app")
  }
}

object Main extends Router {
  import AkkaSystem._

  val config = ConfigFactory.load()  
  val port = config.getInt("geotrellis.port")
  val host = config.getString("geotrellis.hostname")  

  val conf =
    new SparkConf()
      .setAppName("KersService")
      .set("spark.serializer", classOf[org.apache.spark.serializer.KryoSerializer].getName)
      .set("spark.kryo.registrator", classOf[geotrellis.spark.io.kryo.KryoRegistrator].getName)  

  implicit val sc = new SparkContext(conf)

  lazy val (reader, tileReader, attributeStore) = initBackend(config)

  def main(args: Array[String]): Unit = {
    Http().bindAndHandle(routes, host, port)
  }
}
