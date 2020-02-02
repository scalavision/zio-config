package zio.config.typesafe

import com.typesafe.config.ConfigFactory
import zio.config.ConfigSource

import zio.{ ZIO }
import zio.config._
import com.typesafe.config.ConfigValueType
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import scala.collection.JavaConverters._

object TypeSafeConfigSource {
  def hocon(input: Either[com.typesafe.config.Config, String]): ConfigSource[String, String] =
    ConfigSource(
      (path: Vector[String]) => {
        def effect[A](f: => A): Either[ReadError.Unknown[Vector[String]], Option[A]] =
          Try(f) match {
            case Success(value)     => Right(Some(value))
            case Failure(exception) => Left(ReadError.Unknown(path, exception))
          }

        def getLeafValue(
          config: => com.typesafe.config.Config,
          key: String
        ): Either[ReadError.Unknown[Vector[String]], ::[Option[String]]] =
          Try {
            config.getValue(key).valueType()
          } match {
            case Failure(exception) =>
              exception match {
                case e: com.typesafe.config.ConfigException.Missing => Right(::(None, Nil))
                case e                                              => Left(ReadError.Unknown[Vector[String]](path, e))
              }
            case Success(valueType) =>
              if (valueType == ConfigValueType.BOOLEAN) {
                effect(config.getBoolean(key).toString).map(singleton)
              } else if (valueType == ConfigValueType.NULL) {
                Right(::(None, Nil))
              } else if (valueType == ConfigValueType.NUMBER) {
                effect(config.getNumber(key).toString).map(singleton)
              } else if (valueType == ConfigValueType.STRING) {
                effect(config.getString(key)).map(singleton)
              } else if (valueType == ConfigValueType.OBJECT) {
                Left(
                  ReadError.Unknown(
                    path,
                    new RuntimeException(s"The value for the key ${path} is an object and not a primitive.")
                  )
                )
              } else if (valueType == ConfigValueType.LIST) {
                asListOfString(config.getStringList(key))
                  .orElse(
                    asListOfString(config.getIntList(key))
                  )
                  .orElse(
                    asListOfString(config.getBooleanList(key))
                  )
                  .orElse(
                    asListOfString(config.getDurationList(key))
                  )
                  .orElse(
                    asListOfString(config.getBytesList(key))
                  )
                  .orElse(
                    asListOfString(config.getDoubleList(key))
                  )
                  .orElse(
                    asListOfString(config.getLongList(key))
                  )
                  .orElse(
                    asListOfString(config.getMemorySizeList(key))
                  ) match {
                  case Failure(exception) =>
                    Left(
                      ReadError.Unknown(
                        path,
                        new RuntimeException(
                          "Trying to parse a list of config. However, the type is unidentified. Supports only [list] of [int, boolean, duration, bytes, double, long, memory size]",
                          exception
                        )
                      )
                    )
                  case Success(value) =>
                    value match {
                      case h :: t => Right(::(Some(h), t.map(Some(_))))
                      case Nil =>
                        Left(
                          ReadError.Unknown(
                            path,
                            new RuntimeException("List is empty. Only non empty list is supported through zio-config")
                          )
                        )
                    }
                }
              } else {
                Left((ReadError.Unknown(path, new RuntimeException("Unknown type"))))
              }
          }

        def loop(
          parentConfig: com.typesafe.config.Config,
          list: List[String],
          nextPath: List[String]
        ): Either[ReadError.Unknown[Vector[String]], ::[Option[String]]] =
          list match {
            case Nil =>
              nextPath.lastOption match {
                case Some(lastKey) =>
                  val r = getLeafValue(parentConfig, lastKey)
                  r.map(t => {
                    ::(t.head, t.tail)
                  })
                case None =>
                  Right(::(None: Option[String], Nil))
              }

            case head :: next => {
              for {
                res <- Try(parentConfig.getValue(head).valueType()) match {
                        case Failure(r) => Right(::(None, Nil))
                        case Success(valueType) =>
                          if (valueType == ConfigValueType.LIST) {
                            for {
                              // A few extra error handling.
                              res <- effect(parentConfig.getConfigList(head).asScala.toList) match {
                                      case Right(allConfigs) =>
                                        seqEither({
                                          allConfigs.toList.flatten
                                            .map(eachConfig => loop(eachConfig, next, nextPath :+ head))
                                        }).map(t => t.flatMap(_.toList))
                                          .flatMap({
                                            case Nil =>
                                              Right(::(None, Nil))
                                            case h :: t => Right(::(h, t))
                                          })

                                      case Left(_) =>
                                        effect(parentConfig.getList(head).asScala.toList)
                                          .map(_.toList.flatten)
                                          .flatMap {
                                            {
                                              case Nil =>
                                                Right(::(None, Nil))

                                              case h :: t
                                                  if (::(h, t).forall(
                                                    t =>
                                                      t.valueType() != ConfigValueType.NULL ||
                                                        t.valueType() != ConfigValueType.LIST ||
                                                        t.valueType() != ConfigValueType.OBJECT
                                                  )) =>
                                                Right(
                                                  ::(
                                                    Some(h.unwrapped().toString),
                                                    t.map(t => Some(t.unwrapped().toString))
                                                  )
                                                )

                                              case _ =>
                                                Left(
                                                  ReadError.Unknown[Vector[String]](
                                                    path,
                                                    new RuntimeException(
                                                      s"Wrong types in the list. Identified the value of ${head} in HOCON as a list, however, it should be a list of primitive values. Ex: [1, 2, 3]"
                                                    )
                                                  )
                                                )

                                            }
                                          }
                                    }
                            } yield res
                          } else {
                            if (parentConfig.getValue(head).valueType() == ConfigValueType.OBJECT) {
                              loop(parentConfig.getConfig(head), next, nextPath :+ head)
                            } else if (next.isEmpty) {
                              loop(parentConfig, next, nextPath :+ head)
                            } else {
                              Right(::(None, Nil))
                            }
                          }
                      }
              } yield res
            }
          }

        for {
          config <- ZIO
                     .effect(
                       input.fold(
                         config => config,
                         str => ConfigFactory.parseString(str).resolve
                       )
                     )
                     .mapError(throwable => ReadError.Unknown[Vector[String]](path, throwable))

          res <- ZIO.fromEither(loop(config, path.toList, Nil)).map(t => ConfigValue(t))
        } yield Some(res)
      },
      List("typesafe-config-hocon")
    )

  private def asListOfString[A](jlist: => java.util.List[A]): Try[List[String]] =
    Try(jlist).map(_.asScala.toList.map(_.toString))
}
