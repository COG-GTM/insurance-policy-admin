.PHONY: compat compat-static compat-api compat-build

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
	$(COMPOSE) up -d --wait --wait-timeout 300
	@printf '%s\n' \
		'Policy service: http://localhost:8080' \
		'  liveness:   http://localhost:8080/actuator/health/liveness' \
		'  readiness:  http://localhost:8080/actuator/health/readiness' \
		'  prometheus: http://localhost:8080/actuator/prometheus' \
		'  ping:       http://localhost:8080/api/v2/ping' \
		'Batch: http://localhost:8081' \
		'  liveness:   http://localhost:8081/actuator/health/liveness' \
		'  readiness:  http://localhost:8081/actuator/health/readiness' \
		'  prometheus: http://localhost:8081/actuator/prometheus' \
		'PostgreSQL: localhost:5432' \
		'Kafka: localhost:9092'

dev:
	$(MAKE) dev-images
	$(MAKE) dev-up

dev-down:
	$(COMPOSE) down -v --remove-orphans

dev-logs:
	$(COMPOSE) logs -f --tail=200

dev-ps:
	$(COMPOSE) ps
