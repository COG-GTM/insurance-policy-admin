.PHONY: compat compat-static compat-api compat-build dev build fmt down logs

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
	@echo "Policy service: http://localhost:8080"
	@echo "Liveness:       http://localhost:8081/actuator/health/liveness"
	@echo "Readiness:      http://localhost:8081/actuator/health/readiness"
	@echo "Prometheus:     http://localhost:8081/actuator/prometheus"

build:
	cd pas && ./mvnw -B verify

fmt:
	cd pas && ./mvnw -B spotless:apply

down:
	docker compose down -v

logs:
	docker compose logs -f
