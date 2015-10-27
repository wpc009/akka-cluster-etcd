scalaVersion := "2.11.7"

lazy val commonSettings = Seq(
//    version := "0.1.0",
    organization := "pl.caltha",
    scalaVersion := "2.11.7"
)

val akkaVersion = "2.4.0"
val akkaStreamsVersion = "1.0"
val scalaTestVersion = "2.2.5"
val mocitoVersion = "1.10.19"

val publish_settings = Seq(
  publishTo := {
    if (isSnapshot.value)
      Some("snapshots" at "http://artifactory.segmetics.com/artifactory/libs-snapshot-local")
    else
      Some("artifactory.segmetics.com-releases" at "http://artifactory.segmetics.com/artifactory/libs-release-local")
  },
  credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")
)


lazy val client = project. 
    in(file("etcd-client")).
    enablePlugins(GitVersioning).
    settings(commonSettings ++ publish_settings ++ Seq(
        name := "etcd-client",
        libraryDependencies ++= Seq(
            "com.typesafe.akka" %% "akka-actor" % akkaVersion,
            "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
            "com.typesafe.akka" %% "akka-http-experimental" % akkaStreamsVersion,
            "com.typesafe.akka" %% "akka-http-spray-json-experimental" % akkaStreamsVersion,
            "com.typesafe.akka" %% "akka-stream-testkit-experimental" % akkaStreamsVersion % "test",
            "org.scalatest" %% "scalatest" % scalaTestVersion % "test",
            "org.mockito" % "mockito-core" % mocitoVersion % "test"
        )
    ))

import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm    

lazy val discovery = project. 
    in(file("etcd-discovery")).
    dependsOn(client).
    enablePlugins(GitVersioning).
    settings(commonSettings ++ SbtMultiJvm.multiJvmSettings ++ publish_settings ++ Seq(
        name := "akka-cluster-discovery-etcd",
        libraryDependencies ++= Seq(
            "com.typesafe.akka" %% "akka-actor" % akkaVersion,
            "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
            "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaVersion % "test",
            "org.scalatest" %% "scalatest" % scalaTestVersion % "test",
            "org.mockito" % "mockito-core" % mocitoVersion % "test"
        ),
        // make sure that MultiJvm test are compiled by the default test compilation
	    compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
	    // disable parallel tests
	    parallelExecution in Test := false,
	    // make sure that MultiJvm tests are executed by the default test target, 
	    // and combine the results from ordinary test and multi-jvm tests
	    executeTests in Test <<= (executeTests in Test, executeTests in MultiJvm) map {
	      case (testResults, multiNodeResults)  =>
	        val overall =
	          if (testResults.overall.id < multiNodeResults.overall.id)
	            multiNodeResults.overall
	          else
	            testResults.overall
	        Tests.Output(overall,
	          testResults.events ++ multiNodeResults.events,
	          testResults.summaries ++ multiNodeResults.summaries)
	    }
	)).
	configs(MultiJvm)

lazy val clusterMonitor = project.
    in(file("examples/cluster-monitor")).
    dependsOn(discovery).
    settings(commonSettings ++ Seq(
            name := "cluster-monitor",
            mainClass in Compile := Some("akka.Main"),
            javaOptions in Universal ++= Seq(
                "pl.caltha.akka.cluster.monitor.Main"
            ),
            dockerBaseImage := "java:8-jre",
            packageName in Docker := "caltha/akka-cluster-etcd/monitor",
            version in Docker := "latest"
        )
    ).
    enablePlugins(JavaAppPackaging, DockerPlugin)


