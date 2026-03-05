#!/bin/bash
# Setup script to copy EK jars to lib folder
# Run this script from the TFL4 project directory

EK_LIB_DIR="/mnt/c/Users/mme/Desktop/TravicEbicsApi_Java_V4.0.9/lib"
PROJECT_LIB_DIR="$(dirname "$0")/lib"

mkdir -p "$PROJECT_LIB_DIR"

echo "Copying EK jars to lib folder..."

cp -n "$EK_LIB_DIR"/*.jar "$PROJECT_LIB_DIR/" 2>/dev/null || true
cp -n "$EK_LIB_DIR"/*.dll "$PROJECT_LIB_DIR/" 2>/dev/null || true

echo ""
echo "Done! You can now build the project with: mvn clean package -DskipTests"
