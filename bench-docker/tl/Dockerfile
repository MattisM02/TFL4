FROM eclipse-temurin:25-jre

RUN apt-get update \
 && apt-get install -y bash dos2unix tar gzip \
 && rm -rf /var/lib/apt/lists/*

# Runtime installieren
WORKDIR /opt
COPY runtime/travic-runtime-linux64-3.1.33.tar.gz /opt/runtime.tar.gz
RUN mkdir -p /opt/travic-runtime \
 && tar -xzf /opt/runtime.tar.gz -C /opt/travic-runtime --strip-components=1 \
 && rm /opt/runtime.tar.gz

# Wrapper Startscript
COPY start-tl.sh /usr/local/bin/start-tl.sh
RUN dos2unix /usr/local/bin/start-tl.sh \
 && chmod +x /usr/local/bin/start-tl.sh

ENTRYPOINT ["/usr/local/bin/start-tl.sh"]