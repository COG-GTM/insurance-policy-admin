.PHONY: compat compat-static compat-api compat-build parity parity-cobol parity-golden parity-java parity-extracts parity-extracts-golden

compat: compat-static compat-build compat-api

compat-static:
	python3 compat/check_schema.py
	python3 compat/check_layouts.py
	python3 compat/check_security.py

compat-build:
	./compat/build_modules.sh

compat-api:
	./compat/api/run_facade.sh

.PHONY: build test dev dev-images dev-up dev-down dev-logs dev-ps

PAS_DIR := pas-platform
COMPOSE := docker compose -f $(PAS_DIR)/docker-compose.yml
MAVEN_SETTINGS ?= $(HOME)/.m2/settings.xml
MVNW_REPOURL ?=
comma := ,

build:
	cd $(PAS_DIR) && ./mvnw -B -DskipTests verify

test:
	cd $(PAS_DIR) && ./mvnw -B verify

dev-images:
	DOCKER_BUILDKIT=1 docker build $(if $(wildcard $(MAVEN_SETTINGS)),--secret id=maven_settings$(comma)src=$(MAVEN_SETTINGS)) -f $(PAS_DIR)/pas-policy-service/Dockerfile -t pas-policy-service:local --build-arg MVNW_REPOURL="$(MVNW_REPOURL)" $(PAS_DIR)
	DOCKER_BUILDKIT=1 docker build $(if $(wildcard $(MAVEN_SETTINGS)),--secret id=maven_settings$(comma)src=$(MAVEN_SETTINGS)) -f $(PAS_DIR)/pas-batch/Dockerfile -t pas-batch:local --build-arg MVNW_REPOURL="$(MVNW_REPOURL)" $(PAS_DIR)

dev-up:
	@test -f $(PAS_DIR)/.env || { cp $(PAS_DIR)/.env.example $(PAS_DIR)/.env; echo "Created $(PAS_DIR)/.env from .env.example (local-only defaults)"; }
	@rotated=$$($(PAS_DIR)/dev/dev-jwt.sh keygen) || exit 1; \
	$(COMPOSE) up -d --wait --wait-timeout 300 && \
	if [ "$$rotated" = rotated ]; then \
		echo "Dev JWT key pair changed; recreating pas-policy-service"; \
		$(COMPOSE) up -d --wait --wait-timeout 300 --force-recreate pas-policy-service; \
	fi
	@policy=$$($(COMPOSE) port pas-policy-service 8080); batch=$$($(COMPOSE) port pas-batch 8081); \
	pg=$$($(COMPOSE) port postgres 5432); kafka=$$($(COMPOSE) port kafka 9092); \
	printf '%s\n' \
		"Policy service: http://$$policy" \
		"  liveness:   http://$$policy/actuator/health/liveness" \
		"  readiness:  http://$$policy/actuator/health/readiness" \
		"  prometheus: http://$$policy/actuator/prometheus (bearer JWT)" \
		"  inquiry:    http://$$policy/api/v1/policies/{policyNumber} (bearer JWT: $(PAS_DIR)/dev/dev-jwt.sh mint)" \
		"Batch: http://$$batch" \
		"  liveness:   http://$$batch/actuator/health/liveness" \
		"  readiness:  http://$$batch/actuator/health/readiness" \
		"  prometheus: http://$$batch/actuator/prometheus" \
		"PostgreSQL: $$pg" \
		"Kafka: $$kafka"

dev:
	$(MAKE) dev-images
	$(MAKE) dev-up

dev-down:
	$(COMPOSE) down -v --remove-orphans

dev-logs:
	$(COMPOSE) logs -f --tail=200

dev-ps:
	$(COMPOSE) ps

parity: parity-cobol parity-java parity-extracts

parity-cobol:
	python3 -m unittest discover -s parity/tools -p 'test_*.py' && python3 parity/tools/cobol_harness.py check

parity-golden:
	python3 parity/tools/cobol_harness.py update

parity-java:
	cd pas-platform && ./mvnw -B -ntp -pl pas-rating,pas-policy-service,pas-batch -am -Dtest='*Parity*Test,*ParityHarness*Test' -Dsurefire.failIfNoSpecifiedTests=false test

# AB-527: oracle goldens are current, then the Java jobs reproduce them byte for byte (needs Docker).
parity-extracts:
	python3 parity/extracts/oracle.py check
	cd pas-platform && ./mvnw -B -ntp -pl pas-batch -am -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test='ExtractParityIT' -Dspotbugs.skip verify

parity-extracts-golden:
	python3 parity/extracts/oracle.py update
