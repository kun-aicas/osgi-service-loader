In order to build this example just execute

  mvn clean package

in the current directory.  The built bundle can then be found in the file

  target/${project.artifactId}-${project.version}.jar

and can be installed in an OSGi framework.

System requirements for building the example Bundle:

Minimum required Maven Version: 3.6.3
Minimum required Java Version : 17
Internet connection: Yes
