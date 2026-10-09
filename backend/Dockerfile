FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY src ./src
COPY lib ./lib
COPY db ./db
RUN javac -d out src/*.java
EXPOSE 8090
CMD ["java", "-cp", "out:lib/*", "Main"]
