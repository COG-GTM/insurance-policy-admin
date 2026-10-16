.PHONY: compat compat-static compat-api compat-build dev build fmt down logs

PAS_HTTP_PORT ?= 8080
PAS_MANAGEMENT_PORT ?= 8081
PAS_DB_PORT ?= 5432

export PAS_HTTP_PORT PAS_MANAGEMENT_PORT PAS_DB_PORT

compat: compat-static compat-build compat-api

compat-static:
	python3 compat/check_schema.py
	python3 compat/check_layouts.py
	python3 compat/check_security.py

compat-build:
	./compat/build_modules.sh

compat-api:
	./compat/api/run_facade.sh

dev:
	cd pas && ./mvnw -B verify
	docker compose up --build --wait
	@echo "Policy service: http://localhost:$(PAS_HTTP_PORT)"
	@echo "Liveness:       http://localhost:$(PAS_MANAGEMENT_PORT)/actuator/health/liveness"
	@echo "Readiness:      http://localhost:$(PAS_MANAGEMENT_PORT)/actuator/health/readiness"
	@echo "Prometheus:     http://localhost:$(PAS_MANAGEMENT_PORT)/actuator/prometheus"

build:
	cd pas && ./mvnw -B verify

fmt:
	cd pas && ./mvnw -B spotless:apply

down:
	docker compose down -v

logs:
	docker compose logs -f
