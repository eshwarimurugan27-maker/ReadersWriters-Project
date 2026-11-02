FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY ReadersWriters.java .

RUN javac ReadersWriters.java

EXPOSE 10000

CMD ["java", "ReadersWriters"]