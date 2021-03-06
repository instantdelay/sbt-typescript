package name.devries.sbt.typescript


import com.typesafe.sbt.jse.JsEngineImport.JsEngineKeys
import com.typesafe.sbt.jse.SbtJsTask
import com.typesafe.sbt.web.PathMapping
import com.typesafe.sbt.web.pipeline.Pipeline
import sbt.{File, _}
import sbt.Keys._
import spray.json._
import com.typesafe.sbt.jse.SbtJsTask.autoImport.JsTaskKeys._
import com.typesafe.sbt.web.Import.WebKeys._
import com.typesafe.sbt.web.SbtWeb.autoImport._

/** typescript compilation can run during 'sbt assets' compilation or during Play 'sbt stage' as a sbt-web pipe */
sealed class CompileMode(val value:String){
  override def toString=value
}

object CompileMode {

  case object Compile extends CompileMode("compile")

  case object Stage extends CompileMode("stage")
  val values:Set[CompileMode] = Set(Compile,Stage)
  val parse = values.map(v => v.value -> v).toMap
}

object SbtTypescript extends AutoPlugin with JsonProtocol with JsTask {

  override def requires = SbtJsTask

  override def trigger = AllRequirements

  /** the public api to a projects build.sbt */
  object autoImport {
    val typescript = TaskKey[Seq[File]]("typescript", "Run Typescript compiler")

    val projectFile = SettingKey[File]("typescript-projectfile",
      "The location of the tsconfig.json  Default: <basedir>/tsconfig.json")

    val typingsFile = SettingKey[Option[File]]("typescript-typings-file", "A file that refers to typings that the build needs. Default None.")

    val tsCodesToIgnore = SettingKey[List[Int]]("typescript-codes-to-ignore",
      "The tsc error codes (f.i. TS2307) to ignore. Default empty list.")

    val canNotFindModule = 2307 //see f.i. https://github.com/Microsoft/TypeScript/issues/3808

    val resolveFromWebjarsNodeModulesDir = SettingKey[Boolean]("typescript-resolve-modules-from-etc", "Will use the directory to resolve modules ")

    val typescriptPipe = Def.taskKey[Pipeline.Stage]("typescript-stage")
    val outFile = SettingKey[String]("typescript-out-file", "the name of the outfile that the stage pipe will produce. Default 'main.js' ")

    val compileMode = SettingKey[CompileMode]("typescript-compile-mode","the compile mode to use if no jvm argument is provided. Default 'Compile'")
  }

  val getTsConfig = TaskKey[JsObject]("get-tsconfig", "parses the tsconfig.json file")

  val getCompileMode = TaskKey[CompileMode]("get-compile-mode", "determines required compile mode")

  import autoImport._


  override def projectSettings = Seq(
    tsCodesToIgnore := List.empty[Int],
    projectFile := baseDirectory.value / "tsconfig.json",
    typingsFile := None,
    resolveFromWebjarsNodeModulesDir := false,
    logLevel in typescript := Level.Info,
    typescriptPipe := typescriptPipeTask.value,
    JsEngineKeys.parallelism := 1,
    compileMode := CompileMode.Compile,
    getCompileMode := getCompileModeTask.value,
    outFile := "main.js"
  ) ++ inTask(typescript)(
    SbtJsTask.jsTaskSpecificUnscopedSettings ++
      inConfig(Assets)(typescriptUnscopedSettings(Assets)) ++
      inConfig(TestAssets)(typescriptUnscopedSettings(TestAssets)) ++
      Seq(
        moduleName := "typescript",
        shellFile := getClass.getClassLoader.getResource("typescript.js"),

        taskMessage in Assets := "Typescript compiling",
        taskMessage in TestAssets := "Typescript test compiling"
      )
  ) ++ addJsSourceFileTasks() ++ Seq(
    typescript in Assets := (typescript in Assets).dependsOn(webJarsNodeModules in Assets).value,
    typescript in TestAssets := (typescript in TestAssets).dependsOn(webJarsNodeModules in TestAssets).value
  )

  def typescriptUnscopedSettings(config: Configuration) = {

    def optionalFields(m: Map[String, Option[JsValue]]): Map[String, JsValue] = {
      m.flatMap { case (s, oj) => oj match {
        case None => Map.empty[String, JsValue]
        case Some(jsValue) => Map(s -> jsValue)
      }
      }
    }

    Seq(
      includeFilter := GlobFilter("*.ts") | GlobFilter("*.tsx"),
      excludeFilter := GlobFilter("*.d.ts"),
      jsOptions := JsObject(Map(
        "logLevel" -> JsString(logLevel.value.toString),
        "tsconfig" -> parseTsConfig().value,
        "tsconfigDir" -> JsString(projectFile.value.getParent),
        "assetsDir" -> JsString((sourceDirectory in config).value.getAbsolutePath),
        "tsCodesToIgnore" -> JsArray(tsCodesToIgnore.value.toVector.map(JsNumber(_))),
        "nodeModulesDir" -> JsString(webJarsNodeModulesDirectory.value.getAbsolutePath),
        "resolveFromNodeModulesDir" -> JsBoolean(resolveFromWebjarsNodeModulesDir.value),
        "runMode" -> JsString(getCompileMode.value.toString)
      ) ++ optionalFields(Map(
        "extraFiles" -> typingsFile.value.map(tf => JsArray(JsString(tf.getCanonicalPath))),
        "stageOutFile" -> {if (getCompileMode.value == CompileMode.Stage) Some(JsString(outFile.value)) else None }
      )
      )
      ).toString()
    )
  }

  def moveFilesTask(config: Configuration) = Def.task {
    streams.value.log.debug(s"typescript compilation mode is ${getCompileMode.value}")

    val compiledFiles = (typescript in config).value
    //    streams.value.log.info("received files"+compiledFiles)
    //    streams.value.log.info("base "+baseDirectory.value)
    //    streams.value.log.info("source "+(sourceDirectory in config).value)
    val relAssetsPath = (sourceDirectory in config).value.relativeTo(baseDirectory.value)

    val targetPath = (resourceManaged in typescript in config).value
    val movedFiles = moveFiles(compiledFiles, targetPath, relAssetsPath.get.getPath, streams.value.log)
    //    streams.value.log.info("moved files "+movedFiles.toString())
    movedFiles
  }

  def moveFiles(compiledFiles: Seq[File], targetPath: File, relAssetsPath: String, logger: Logger): Seq[File] = {
    // input is target/web/typescript/main/src/main/assets/x/y output is target/web/typescript/main/x/y
    val copyMappings: Seq[(File, File)] = compiledFiles.map(f => {
      val relativeToPath = targetPath / relAssetsPath
      val relFilePath = f relativeTo relativeToPath

      //logger.info(s"file $f relative to $relativeToPath is $relFilePath")
      val targetFile = targetPath / relFilePath.getOrElse(throw new IllegalStateException(s"can't resolve $f relative to $relativeToPath")).getPath
      (f, targetFile)
    })
    IO.copy(copyMappings).toSeq
  }

  def parseTsConfig() = Def.task {

    def removeComments(string: String) = {
      // cribbed from http://blog.ostermiller.org/find-comment
      string.replaceAll("""/\*([^*]|[\r\n]|(\*+([^*/]|[\r\n])))*\*+/""", "")
    }

    def parseJson(tsConfigFile: File): JsValue = {
      val content = IO.read(tsConfigFile)

      JsonParser(removeComments(content))
    }

    val tsConfigFile = projectFile.value

    val tsConfigObject =parseJson(tsConfigFile).asJsObject
    val newTsConfigObject = for{
      coJsValue <- tsConfigObject.fields.get("compilerOptions") if getCompileMode.value == CompileMode.Stage

      co = coJsValue.asJsObject
      newCo = JsObject(co.fields - "outDir" ++ Map("outFile"->JsString(outFile.value)))
    }yield  JsObject(tsConfigObject.fields ++ Map("compilerOptions"-> newCo))
      newTsConfigObject.getOrElse(tsConfigObject)
  }

  def getCompileModeTask = Def.task{
    val modeOpt=for {
      s <- sys.props.get("tsCompileMode")
      cm <- CompileMode.parse.get(s)
    }yield cm

    modeOpt.getOrElse(compileMode.value)
  }

  def typescriptPipeTask: Def.Initialize[Task[Pipeline.Stage]] = Def.task {
    inputMappings =>


      val isTypescript:PathMapping => Boolean ={case (file,path)=> (includeFilter in typescript in Assets).value.accept(file)}
      val minustypescriptMappings = inputMappings.filterNot(isTypescript)
      streams.value.log.debug(s"running typescript pipe")

      minustypescriptMappings
  }

}
