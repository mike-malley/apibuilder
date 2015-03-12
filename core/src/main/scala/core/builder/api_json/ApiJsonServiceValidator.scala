package builder.api_json

import builder.ServiceValidator
import core.{ClientFetcher, Importer, ServiceConfiguration, ServiceFetcher, Util}
import lib.{Datatype, Methods, Primitives, Text, Type, Kind, UrlKey}
import com.gilt.apidoc.spec.v0.models.{Enum, Field, Method, Service}
import play.api.libs.json.{JsObject, Json, JsValue}
import com.fasterxml.jackson.core.{ JsonParseException, JsonProcessingException }
import com.fasterxml.jackson.databind.JsonMappingException
import scala.util.{Failure, Success, Try}

case class ApiJsonServiceValidator(
  config: ServiceConfiguration,
  apiJson: String,
  fetcher: ServiceFetcher = new ClientFetcher()
) extends ServiceValidator {

  private val RequiredFields = Seq("name")

  lazy val service: Service = ServiceBuilder(config, internalService.get)

  def validate(): Either[Seq[String], Service] = {
    if (isValid) {
      Right(service)
    } else {
      Left(errors)
    }
  }

  private var parseError: Option[String] = None

  lazy val serviceForm: Option[JsObject] = {
    Try(Json.parse(apiJson)) match {
      case Success(v) => {
        v.asOpt[JsObject] match {
          case Some(o) => {
            Some(o)
          }
          case None => {
            parseError = Some("Must upload a Json Object")
            None
          }
        }
      }
      case Failure(ex) => ex match {
        case e: JsonParseException => {
          parseError = Some(e.getMessage)
          None
        }
        case e: JsonProcessingException => {
          parseError = Some(e.getMessage)
          None
        }
      }
    }
  }

  private lazy val internalService: Option[InternalServiceForm] = serviceForm.map(InternalServiceForm(_, fetcher))

  lazy val errors: Seq[String] = internalErrors match {
    case Nil => builder.ServiceSpecValidator(service).errors
    case e => e
  }

  private lazy val internalErrors: Seq[String] = {
    internalService match {

      case None => {
        if (apiJson.trim == "") {
          Seq("No Data")
        } else {
          Seq(parseError.getOrElse("Invalid JSON"))
        }
      }

      case Some(sd: InternalServiceForm) => {
        val requiredFieldErrors = validateRequiredFields()

        if (requiredFieldErrors.isEmpty) {
          validateKey ++
          validateImports ++
          validateEnums ++
          validateUnions ++
          validateHeaders ++
          validateFields ++
          validateOperations ++
          validateParameterBodies ++
          validateParameters ++
          validateResponses ++
          validatePathParameters ++
          validatePathParametersAreRequired

        } else {
          requiredFieldErrors
        }
      }
    }
  }

  private def validateKey(): Seq[String] = {
    internalService.get.key match {
      case None => Seq.empty
      case Some(key) => {
        val generated = UrlKey.generate(key)
        if (generated == key) {
          Seq.empty
        } else {
          Seq(s"Invalid url key. A valid key would be $generated")
        }
      }
    }
  }

  /**
   * Validate basic structure, returning a list of error messages
   */
  private def validateRequiredFields(): Seq[String] = {
    val missing = RequiredFields.filter { field =>
      (internalService.get.json \ field).asOpt[JsValue] match {
        case None => true
        case Some(_) => false
      }
    }
    if (missing.isEmpty) {
      Seq.empty
    } else {
      Seq("Missing: " + missing.mkString(", "))
    }
  }

  private def validateImports(): Seq[String] = {
    internalService.get.imports.flatMap { imp =>
      imp.uri match {
        case None => Seq("imports.uri is required")
        case Some(uri) => {
          Util.validateUri(uri) match {
            case Nil => Importer(fetcher, uri).validate  // TODO. need to cache somewhere to avoid a second lookup when parsing later
            case errors => errors
          }
        }
      }
    }
  }

  private def validateEnums(): Seq[String] = {
    internalService.get.enums.flatMap { enum =>
      enum.values.filter(_.name.isEmpty).map { value =>
        s"Enum[${enum.name}] - all values must have a name"
      }
    }
  }

  private def validateUnions(): Seq[String] = {
    internalService.get.unions.filter { !_.types.filter(_.datatype.isEmpty).isEmpty }.map { union =>
      s"Union[${union.name}] all types must have a name"
    }
  }

  private def validateHeaders(): Seq[String] = {
    val headersWithoutNames = internalService.get.headers.filter(_.name.isEmpty) match {
      case Nil => Seq.empty
      case headers => Seq("All headers must have a name")
    }

    val headersWithoutTypes = internalService.get.headers.filter(_.datatype.isEmpty) match {
      case Nil => Seq.empty
      case headers => Seq("All headers must have a type")
    }

    headersWithoutNames ++ headersWithoutTypes
  }

  private def validateFields(): Seq[String] = {
    val missingNames = internalService.get.models.flatMap { model =>
      model.fields.filter(_.name.isEmpty).map { f =>
        s"Model[${model.name}] field[${f.name}] must have a name"
      }
    }

    val missingTypes = internalService.get.models.flatMap { model =>
      model.fields.filter(!_.name.isEmpty).filter(_.datatype.isEmpty).map { f =>
        s"Model[${model.name}] field[${f.name.get}] must have a type"
      }
    }

    val warnings = internalService.get.models.flatMap { model =>
      model.fields.filter(f => !f.warnings.isEmpty && !f.name.isEmpty).map { f =>
        s"Model[${model.name}] field[${f.name.get}]: " + f.warnings.mkString(", ")
      }
    }

    missingTypes ++ missingNames ++ warnings
  }

  private def validateResponses(): Seq[String] = {
    val invalidMethods = internalService.get.resources.flatMap { resource =>
      resource.operations.flatMap { op =>
        op.method match {
          case None => Seq(s"Resource[${resource.datatype.label}] ${op.path} Missing HTTP method")
          case Some(m) => {
            Method.fromString(m) match {
              case None => Seq(s"Resource[${resource.datatype.label}] ${op.path} Invalid HTTP method[$m]. Must be one of: " + Method.all.mkString(", "))
              case Some(_) => Seq.empty
            }
          }
        }
      }
    }

    val invalidCodes = internalService.get.resources.flatMap { resource =>
      resource.operations.flatMap { op =>
        op.responses.flatMap { r =>
          Try(r.code.toInt) match {
            case Success(v) => None
            case Failure(ex) => ex match {
              case e: java.lang.NumberFormatException => {
                Some(s"Resource[${resource.datatype.label}] ${op.label}: Response code is not an integer[${r.code}]")
              }
            }
          }
        }
      }
    }

    val modelNames = internalService.get.models.map { _.name }.toSet
    val enumNames = internalService.get.enums.map { _.name }.toSet

    val missingOrInvalidTypes = internalService.get.resources.flatMap { resource =>
      resource.operations.flatMap { op =>
        op.responses.flatMap { r =>
          r.datatype.map(_.name) match {
            case None => {
              Some(s"Resource[${resource.datatype.label}] ${op.label} with response code[${r.code}]: Missing type")
            }
            case Some(typeName) => {
              internalService.get.typeResolver.toType(typeName) match {
                case None => Some(s"Resource[${resource.datatype.label}] ${op.label} with response code[${r.code}] has an invalid type[$typeName].")
                case Some(t) => None
              }
            }
          }
        }
      }
    }

    val mixed2xxResponseTypes = if (invalidCodes.isEmpty) {
      internalService.get.resources.flatMap { resource =>
        resource.operations.flatMap { op =>
          val types = op.responses.filter { r => !r.datatypeLabel.isEmpty && r.code.toInt >= 200 && r.code.toInt < 300 }.map(_.datatypeLabel.get).distinct
          if (types.size <= 1) {
            None
          } else {
            Some(s"Resource[${resource.datatype.label}] cannot have varying response types for 2xx response codes: ${types.sorted.mkString(", ")}")
          }
        }
      }
    } else {
      Seq.empty
    }

    val typesNotAllowed = Seq(404) // also >= 500
    val responsesWithDisallowedTypes = if (invalidCodes.isEmpty) {
      internalService.get.resources.flatMap { resource =>
        resource.operations.flatMap { op =>
          op.responses.find { r => typesNotAllowed.contains(r.code.toInt) || r.code.toInt >= 500 } match {
            case None => {
              None
            }
            case Some(r) => {
              Some(s"Resource[${resource.datatype.label}] ${op.label} has a response with code[${r.code}] - this code cannot be explicitly specified")
            }
          }
        }
      }
    } else {
      Seq.empty
    }

    val typesRequiringUnit = Seq(204, 304)
    val noContentWithTypes = if (invalidCodes.isEmpty) {
      internalService.get.resources.flatMap { resource =>
        resource.operations.flatMap { op =>
          op.responses.filter(r => typesRequiringUnit.contains(r.code.toInt) && !r.datatype.isEmpty && r.datatype.get.name != Primitives.Unit.toString).map { r =>
            s"""Resource[${resource.datatype.label}] ${op.label} Responses w/ code[${r.code}] must return unit and not[${r.datatype.get.label}]"""
          }
        }
      }
    } else {
      Seq.empty
    }

    val warnings = internalService.get.resources.flatMap { resource =>
      resource.operations.flatMap { op =>
        op.responses.filter(r => !r.warnings.isEmpty).map { r =>
          s"Resource[${resource.datatype.label}] ${op.method.getOrElse("")} ${r.code}: " + r.warnings.mkString(", ")
        }
      }
    }

    invalidMethods ++ invalidCodes ++ missingOrInvalidTypes ++ mixed2xxResponseTypes ++ responsesWithDisallowedTypes ++ noContentWithTypes ++ warnings
  }

  private def validateParameterBodies(): Seq[String] = {
    internalService.get.resources.flatMap { resource =>
      resource.operations.filter(!_.body.isEmpty).flatMap { op =>
        op.body.flatMap(_.datatype) match {
          case None => Some(s"${opLabel(resource, op)}: Body missing type")
          case Some(_) => None
        }
      }
    }
  }

  private def opLabel(resource: InternalResourceForm, op: InternalOperationForm): String = {
    s"Resource[${resource.datatype.label}] ${op.method.get} ${op.path}"
  }

  private def validateParameters(): Seq[String] = {
    val missingNames = internalService.get.resources.flatMap { resource =>
      resource.operations.flatMap { op =>
        op.parameters.filter(_.name.isEmpty).map { p =>
          s"${opLabel(resource, op)}: Missing name"
        }
        op.parameters.filter(_.datatype.isEmpty).map { p =>
          s"${opLabel(resource, op)}: Missing type"
        }
      }
    }

    // Query parameters can only be primitives or enums
    val invalidQueryTypes = internalService.get.resources.flatMap { resource =>
      resource.operations.filter(!_.method.isEmpty).filter(op => !op.body.isEmpty || op.method.map(Method(_)) == Some(Method.Get) ).flatMap { op =>
        op.parameters.filter(!_.name.isEmpty).filter(!_.datatype.isEmpty).flatMap { p =>
          val dt = p.datatype.get
          internalService.get.typeResolver.parse(dt) match {
            case None => {
              Some(s"${opLabel(resource, op)}: Parameter[${p.name.get}] has an invalid type: ${dt.label}")
            }
            case Some(Datatype.List(Type(Kind.Primitive | Kind.Enum, name))) => {
              None
            }
            case Some(Datatype.List(Type(Kind.Model | Kind.Union, name))) => {
              Some(s"${opLabel(resource, op)}: Parameter[${p.name.get}] has an invalid type[${dt.name}]. Model and union types are not supported as query parameters.")
            }

            case Some(Datatype.Singleton(Type(Kind.Primitive | Kind.Enum, name))) => {
              None
            }
            case Some(Datatype.Singleton(Type(Kind.Model | Kind.Union, name))) => {
              Some(s"${opLabel(resource, op)}: Parameter[${p.name.get}] has an invalid type[${dt.name}]. Model and union types are not supported as query parameters.")
            }

            case Some(Datatype.Map(_)) => {
              Some(s"${opLabel(resource, op)}: Parameter[${p.name.get}] has an invalid type[${dt.label}]. Maps are not supported as query parameters.")
            }
          }
        }
      }
    }

    val unknownTypes = internalService.get.resources.flatMap { resource =>
      resource.operations.filter(!_.method.isEmpty).flatMap { op =>
        op.parameters.filter(!_.name.isEmpty).filter(!_.datatype.isEmpty).flatMap { p =>
          p.datatype.map(_.name) match {
            case None => Some(s"${opLabel(resource, op)}: Parameter[${p.name.get}] is missing a type.")
            case Some(typeName) => None
          }
        }
      }
    }

    missingNames ++ invalidQueryTypes ++ unknownTypes
  }

  private def validateOperations(): Seq[String] = {
    internalService.get.resources.flatMap { resource =>
      resource.operations.filter(!_.warnings.isEmpty).map { op =>
        s"${opLabel(resource, op)}: ${op.warnings.mkString(", ")}"
      }
    }
  }

  private def validatePathParameters(): Seq[String] = {
    internalService.get.resources.flatMap { resource =>
      internalService.get.models.find(_.name == resource.datatype.label) match {
        case None => None
        case Some(model: InternalModelForm) => {
          resource.operations.filter(!_.namedPathParameters.isEmpty).flatMap { op =>
            val fieldMap = model.fields.filter(f => !f.name.isEmpty && !f.datatype.map(_.name).isEmpty).map(f => (f.name.get -> f.datatype.get)).toMap
            val paramMap = op.parameters.filter(p => !p.name.isEmpty && !p.datatype.map(_.name).isEmpty).map(p => (p.name.get -> p.datatype.get)).toMap

            op.namedPathParameters.flatMap { name =>
              val parsedDatatype = paramMap.get(name).getOrElse {
                fieldMap.get(name).getOrElse {
                  InternalDatatype(Primitives.String.toString)
                }
              }
              val errorTemplate = s"Resource[${resource.datatype.label}] ${op.method.getOrElse("")} path parameter[$name] has an invalid type[%s]. Valid types for path parameters are: ${Primitives.ValidInPath.mkString(", ")}"

              internalService.get.typeResolver.parse(parsedDatatype) match {
                case None => Some(errorTemplate.format(name))

                case Some(Datatype.List(_)) => Some(errorTemplate.format("list"))
                case Some(Datatype.Map(_)) => Some(errorTemplate.format("map"))
                case Some(Datatype.Singleton(t)) => {
                  isTypeValidInPath(t) match {
                    case true => None
                    case false => Some(errorTemplate.format(t.name))
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  private def isTypeValidInPath(t: Type): Boolean = {
    t.typeKind match {
      case Kind.Primitive => {
        Primitives.validInPath(t.name)
      }
      case Kind.Model | Kind.Union => {
        // We do not support models in path parameters
        false
      }
      case Kind.Enum => {
        // Serializes as a string
        true
      }
    }
  }

  private def validatePathParametersAreRequired(): Seq[String] = {
    internalService.get.resources.flatMap { resource =>
      internalService.get.models.find(_.name == resource.datatype.label) match {
        case None => None
        case Some(model: InternalModelForm) => {
          resource.operations.filter(!_.namedPathParameters.isEmpty).flatMap { op =>
            val fieldMap = model.fields.filter(f => !f.name.isEmpty && !f.datatype.map(_.name).isEmpty).map(f => (f.name.get -> f.required)).toMap
            val paramMap = op.parameters.filter(p => !p.name.isEmpty && !p.datatype.map(_.name).isEmpty).map(p => (p.name.get -> p.required)).toMap

            op.namedPathParameters.flatMap { name =>
              val isRequired = paramMap.get(name).getOrElse {
                fieldMap.get(name).getOrElse {
                  true
                }
              }

              if (isRequired) {
                None
              } else {
                Some(s"Resource[${resource.datatype.label}] ${op.method.getOrElse("")} path parameter[$name] is specified as optional. All path parameters are required")
              }
            }
          }
        }
      }
    }
  }
}