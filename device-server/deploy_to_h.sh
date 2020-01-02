
set -x
./gradlew clean build
scp build/libs/device-server-1.0-SNAPSHOT.jar automation7.d4:/home/vfrolov
ssh automation7.d4 docker cp /home/vfrolov/device-server-1.0-SNAPSHOT.jar device-server-h:/app/device-server.jar
ssh automation7.d4 docker restart device-server-h
