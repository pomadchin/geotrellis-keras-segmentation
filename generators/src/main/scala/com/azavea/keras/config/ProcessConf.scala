package com.azavea.keras.config

object ProcessConf {
  case class Options(
    layerName: String = "keras-raw",
    catalogPath: String = "/data/keras-ingest",
    zoom: Int = 0,
    tiffSize: Int = 256,
    amount: Int = 5000,
    randomization: Boolean = true,
    zscore: Boolean = true,
    path: String = "/tmp",
    bands: Option[String] = None
  )

  val help = """
               |geotrellis-keras-generators
               |
               |Usage: geotrellis-keras-generators [options]
               |
               |  --layerName <value>
               |        layerName is a non-empty String option [default: keras-raw]
               |  --catalogPath <value>
               |        catalogPath is a non-empty String option [default: /data/keras-ingest]
               |  --zoom <value>
               |        zoom is a non-empty Int option [default: 0]
               |  --tiffSize <value>
               |        tiffSize is a non-empty Int option [default: 256]
               |  --amount <value>
               |        amount is a non-empty Int option [default: 5000]
               |  --randomization <value>
               |        randomization is a non-empty Boolean option [default: true]
               |  --zscore <value>
               |        zscore is a non-empty Boolean option [default: true]
               |  --path <value>
               |        path is a a String option [default: /tmp]
               |  --bands <value>
               |        bands is a String option
               |  --help
               |        prints this usage text
             """.stripMargin

  def nextOption(opts: Options, list: Seq[String]): Options = {
    list.toList match {
      case Nil => opts
      case "--layerName" :: value :: tail =>
        nextOption(opts.copy(layerName = value), tail)
      case "--catalogPath" :: value :: tail =>
        nextOption(opts.copy(catalogPath = value), tail)
      case "--zoom" :: value :: tail =>
        nextOption(opts.copy(zoom = value.toInt), tail)
      case "--tiffSize" :: value :: tail =>
        nextOption(opts.copy(tiffSize = value.toInt), tail)
      case "--amount" :: value :: tail =>
        nextOption(opts.copy(amount = value.toInt), tail)
      case "--randomization" :: value :: tail =>
        nextOption(opts.copy(randomization = value.toBoolean), tail)
      case "--zscore" :: value :: tail =>
        nextOption(opts.copy(zscore = value.toBoolean), tail)
      case "--path" :: value :: tail =>
        nextOption(opts.copy(path = value), tail)
      case "--bands" :: value :: tail =>
        nextOption(opts.copy(bands = Some(value)), tail)
      case "--help" :: tail => {
        println(help)
        sys.exit(1)
      }
      case option :: tail => {
        println(s"Unknown option ${option}")
        println(help)
        sys.exit(1)
      }
    }
  }

  def parse(args: Seq[String]) = nextOption(Options(), args)

  def apply(args: Seq[String]): Options = parse(args)
}
