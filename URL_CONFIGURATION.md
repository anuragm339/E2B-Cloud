# Cloud Server URL Configuration Guide

## Problem: Hardcoded URLs Don't Work Across Different Deployment Environments

### The Issue

Previously, the cloud-server returned hardcoded URLs in the `/registry/topology` endpoint:

```java
response.put("requestToFollow", List.of("http://cloud-server:8080"));
response.put("cloudDataUrl", "http://cloud-server:8080");
```

**Why This Fails**:

1. **Docker Environment** (Container-to-Container):
   - Broker runs inside Docker network
   - DNS resolves `cloud-server` → Works ✅

2. **Localhost Environment** (Host Machine):
   - Your Mac/PC doesn't know what `cloud-server` means
   - No DNS entry for `cloud-server` → Fails ❌

3. **Production Environment**:
   - Need actual domain like `https://api.example.com`
   - Hardcoded `cloud-server` won't work → Fails ❌

## Solution: Configurable Server Public URL

The cloud-server now uses the `SERVER_PUBLIC_URL` environment variable to configure what URL it returns to clients.

### How It Works

**1. Configuration** (`application.yml`):
```yaml
server:
  public:
    url: ${SERVER_PUBLIC_URL:http://cloud-server:8080}
```

**2. Controller** (`CloudServerController.java`):
```java
public CloudServerController(MessageService messageService,
                              @Value("${server.public.url}") String serverPublicUrl) {
    this.serverPublicUrl = serverPublicUrl;
}

// In topology endpoint:
response.put("requestToFollow", List.of(serverPublicUrl));
response.put("cloudDataUrl", serverPublicUrl);
```

**3. Environment Variable** (docker-compose.yml):
```yaml
environment:
  - SERVER_PUBLIC_URL=http://cloud-server:8080
```

## Usage Examples

### Example 1: Docker Deployment (Default)

```yaml
# docker-compose.yml
services:
  cloud-server:
    environment:
      - SERVER_PUBLIC_URL=http://cloud-server:8080  # Use Docker service name
```

**Result**:
```bash
curl http://localhost:8080/registry/topology
# Returns: "cloudDataUrl": "http://cloud-server:8080"
```

Brokers inside Docker network can reach `http://cloud-server:8080` ✅

---

### Example 2: Localhost Development

```yaml
# docker-compose.yml (or override file)
services:
  cloud-server:
    environment:
      - SERVER_PUBLIC_URL=http://localhost:8080  # Use localhost for host access
```

**Result**:
```bash
curl http://localhost:8080/registry/topology
# Returns: "cloudDataUrl": "http://localhost:8080"
```

Your Mac/PC can reach `http://localhost:8080` ✅

---

### Example 3: Production Deployment

```yaml
# docker-compose.yml or kubernetes config
services:
  cloud-server:
    environment:
      - SERVER_PUBLIC_URL=https://messaging.mycompany.com  # Actual domain
```

**Result**:
```bash
curl https://messaging.mycompany.com/registry/topology
# Returns: "cloudDataUrl": "https://messaging.mycompany.com"
```

Clients can reach the public domain ✅

---

### Example 4: Running Locally with Gradle

```bash
# Run cloud-server on host machine (not Docker)
cd cloud-server

# Set environment variable for localhost
export SERVER_PUBLIC_URL=http://localhost:8080

# Run
./gradlew run
```

Now the server will return `http://localhost:8080` in topology responses.

---

### Example 5: Mixed Environment (Docker + Host)

**Scenario**: Broker in Docker, Cloud-server on host machine

```yaml
# docker-compose.yml
services:
  broker:
    environment:
      # Use host.docker.internal to reach host machine from container
      - REGISTRY_URL=http://host.docker.internal:8080
```

```bash
# Start cloud-server on host
cd cloud-server
export SERVER_PUBLIC_URL=http://host.docker.internal:8080
./gradlew run
```

Broker can reach host machine via `host.docker.internal` ✅

## Environment Variable Priority

1. **Environment Variable**: `SERVER_PUBLIC_URL` (highest priority)
2. **Application YAML**: `server.public.url` (medium priority)
3. **Default Value**: `http://cloud-server:8080` (fallback)

Example:
```bash
# Override at runtime
docker run -e SERVER_PUBLIC_URL=http://custom:9000 cloud-server:latest

# Uses: http://custom:9000
```

## Verification

### Check Current Configuration

```bash
# Check logs for configured URL
docker compose logs cloud-server | grep "Server public URL"

# Output:
# [CLOUD] Server public URL configured as: http://cloud-server:8080
```

### Test Topology Endpoint

```bash
# Query topology
curl -s http://localhost:8080/registry/topology?nodeId=test | jq .cloudDataUrl

# Output:
# "http://cloud-server:8080"
```

### Verify Broker Can Reach Cloud Server

```bash
# From inside broker container
docker exec messaging-broker curl http://cloud-server:8080/health

# Should return:
# {"status":"healthy","role":"CLOUD","mode":"TEST","totalMessages":123}
```

## Common Scenarios

### Scenario 1: "I want to test locally without Docker"

```bash
# Terminal 1: Start cloud-server
cd cloud-server
export SERVER_PUBLIC_URL=http://localhost:8080
./gradlew run

# Terminal 2: Start broker
cd provider
export REGISTRY_URL=http://localhost:8080
./gradlew :broker:run
```

---

### Scenario 2: "I'm deploying to AWS with a load balancer"

```yaml
# docker-compose.yml or ECS task definition
environment:
  - SERVER_PUBLIC_URL=https://messaging-api.mycompany.com
```

The load balancer URL will be returned to all brokers.

---

### Scenario 3: "I have multiple cloud servers behind a load balancer"

All cloud-server instances should return the **load balancer URL**, not their individual IPs:

```yaml
environment:
  - SERVER_PUBLIC_URL=https://lb.internal.mycompany.com
```

---

### Scenario 4: "I want to use a different port"

```yaml
services:
  cloud-server:
    ports:
      - "9000:8080"  # External:Internal
    environment:
      - SERVER_PUBLIC_URL=http://cloud-server:9000  # Use external port
```

**Note**: Inside Docker network, containers use internal port (8080), but external clients use 9000.

## Troubleshooting

### Problem: Broker can't connect to cloud-server

**Check 1**: Verify URL in topology response
```bash
curl http://localhost:8080/registry/topology | jq .cloudDataUrl
```

**Check 2**: Verify broker can resolve the URL
```bash
# If URL is "cloud-server:8080", test from broker container:
docker exec messaging-broker curl http://cloud-server:8080/health
```

**Check 3**: Verify network connectivity
```bash
# Check broker is in same Docker network
docker inspect messaging-broker | jq '.[0].NetworkSettings.Networks'
docker inspect cloud-server | jq '.[0].NetworkSettings.Networks'
```

### Problem: URL is still hardcoded

**Check 1**: Verify environment variable is set
```bash
docker inspect cloud-server | jq '.[0].Config.Env' | grep SERVER_PUBLIC_URL
```

**Check 2**: Rebuild image if you changed code
```bash
docker compose build cloud-server
docker compose up -d cloud-server
```

**Check 3**: Check application logs
```bash
docker compose logs cloud-server | grep "Server public URL configured"
```

## Best Practices

1. **Docker Deployment**: Use service names (`cloud-server:8080`)
2. **Local Development**: Use `localhost:8080`
3. **Production**: Use actual domain with HTTPS
4. **Load Balancers**: Always return LB URL, not individual instance IPs
5. **Kubernetes**: Use service DNS names (`cloud-server.messaging.svc.cluster.local`)

## Summary

The `SERVER_PUBLIC_URL` environment variable solves the problem of hardcoded URLs by allowing you to configure the URL that cloud-server returns to clients based on your deployment environment:

- **Docker**: `http://cloud-server:8080`
- **Localhost**: `http://localhost:8080`
- **Production**: `https://api.mycompany.com`
- **Kubernetes**: `http://cloud-server.namespace.svc.cluster.local:8080`

This makes your deployment flexible and works across all environments!
