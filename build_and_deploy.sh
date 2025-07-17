#!/bin/sh

# Version from argument
APP_VERSION=$1

# Save current branch
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)

# Build shadow JAR
./gradlew shadowJar -PappVersion="$APP_VERSION"

# Get path to the JAR file
JAR_FILE=$(find server/build/libs -name "akexorcist-index-updater-*-all.jar")

# Extract version from file name
VERSION=$(basename "$JAR_FILE")
VERSION=${VERSION#akexorcist-index-updater-}
VERSION=${VERSION%-all.jar}

# Move the JAR file to the root and rename it
mv "$JAR_FILE" akexorcist-index-updater-all.jar

# Create/Checkout an orphan deploy branch.
git checkout --orphan deploy

# Clean the working directory for the new branch
git rm -rf .

# Add and commit the JAR
git add akexorcist-index-updater-all.jar
git commit -m "Deploy version $VERSION"

# Push to upstream
git push -f origin deploy

# Switch back to the original branch
git checkout "$CURRENT_BRANCH"
