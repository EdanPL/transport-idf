# Étape 1 : Compilation Maven avec Java 21
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copie du pom.xml en premier pour mettre en cache les dépendances
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copie du code source et génération du .jar (sans relancer les tests)
COPY src ./src
RUN mvn clean package -DskipTests -B

# Étape 2 : Image d'exécution légère (JRE 21 uniquement)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Récupération du .jar compilé depuis l'étape 1
COPY --from=build /app/target/*.jar app.jar

# Port par défaut (surchargé automatiquement par Render/Koyeb via $PORT)
EXPOSE 8080

# Limite la RAM JVM à 75% du conteneur pour éviter les crashs sur les offres gratuites (512 Mo)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]