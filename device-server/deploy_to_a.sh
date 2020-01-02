
set -x
./gradlew clean build
scp build/libs/device-server-1.0-SNAPSHOT.jar automation8.d4:/home/vfrolov
ssh automation8.d4 docker cp /home/vfrolov/device-server-1.0-SNAPSHOT.jar device-server-a:/app/device-server.jar
ssh automation8.d4 docker restart device-server-a
