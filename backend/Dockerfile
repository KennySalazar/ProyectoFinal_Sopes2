FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY src ./src
RUN javac -d out src/*.java
EXPOSE 8090
CMD ["java", "-cp", "out", "Main"]
