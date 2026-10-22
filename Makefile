.PHONY: compat compat-static compat-api compat-api-modern compat-build parity parity-golden parity-golden-check test dev-env dev-images dev dev-down dev-clean local extract-compare load-premium-batch rollback-rehearsal

POLICIES ?= 250000

compat: compat-static compat-build compat-api

compat-static:
	python3 compat/check_schema.py
	python3 compat/check_layouts.py
	python3 compat/check_security.py

compat-build:
	./compat/build_modules.sh

compat-api:
	./compat/api/run_facade.sh

compat-api-modern:
	./compat/api/run_modern.sh

parity:
	python3 parity/tools/harness.py check
	python3 -m unittest discover -s parity/tools
	cd pas-platform && ./mvnw -B -ntp -pl pas-policy-service -am test

parity-golden:
	python3 parity/tools/harness.py golden

parity-golden-check: parity-golden
	git diff --exit-code -- parity/golden
	test -z "$$(git ls-files --others --exclude-standard -- parity/golden)"

test:
	cd pas-platform && ./mvnw -B verify

dev-env:
	cd pas-platform && if [ ! -f .env ]; then cp .env.example .env; fi
	cd pas-platform && if ! rg -q '^PAS_ENV=' .env; then printf 'PAS_ENV=local\n' >> .env; fi
	cd pas-platform && \
	for key in PAS_PII_ENCRYPTION_KEY_ID PAS_PII_ENCRYPTION_KEY_VERSION PAS_PII_ENCRYPTION_KEY PAS_PII_BLIND_INDEX_KEY PAS_RECON_FINGERPRINT_KEY PAS_MIGRATION_SOURCE_PASSWORD PAS_MIGRATION_REVERSE_PASSWORD; do \
		if ! rg -q "^$${key}=" .env; then \
			case "$$key" in \
				PAS_PII_ENCRYPTION_KEY_ID) value=local-v1 ;; \
				PAS_PII_ENCRYPTION_KEY_VERSION) value=1 ;; \
				PAS_MIGRATION_SOURCE_PASSWORD|PAS_MIGRATION_REVERSE_PASSWORD) value="$$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')" ;; \
				*) value="$$(python3 -c 'import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())')" ;; \
			esac; \
			printf '%s=%s\n' "$$key" "$$value" >> .env; \
		fi; \
	done
	cd pas-platform && \
	for key in PAS_MIGRATION_SOURCE_PASSWORD PAS_MIGRATION_REVERSE_PASSWORD; do \
		if ! rg -q "^$${key}=.+" .env || rg -q "^$${key}=changeme$$" .env; then \
			value="$$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"; \
			sed -i "s|^$${key}=.*$$|$${key}=$$value|" .env; \
		fi; \
	done
	cd pas-platform && sed -i 's/^PAS_ENV=.*/PAS_ENV=local/' .env
	cd pas-platform && ./dev/dev-jwt.sh keygen

dev-images:
	BUILD_ARGS=""; \
	if [ -n "$(MVNW_REPOURL)" ]; then BUILD_ARGS="$$BUILD_ARGS --build-arg MVNW_REPOURL=$(MVNW_REPOURL)"; fi; \
	if [ -n "$(MAVEN_SETTINGS)" ] && [ -f "$(MAVEN_SETTINGS)" ]; then \
		BUILD_ARGS="$$BUILD_ARGS --secret id=maven_settings,src=$(abspath $(MAVEN_SETTINGS))"; \
	fi; \
	docker build $$BUILD_ARGS -f pas-platform/pas-policy-service/Dockerfile -t pas-policy-service:local . && \
	docker build $$BUILD_ARGS -f pas-platform/pas-batch/Dockerfile -t pas-batch:local .

dev: dev-env dev-images
	cd pas-platform && docker compose up -d --wait
	@cd pas-platform && printf 'Policy readiness: http://localhost:%s/actuator/health/readiness\n' "$$(docker compose port pas-policy-service 8080 | sed 's/.*://')"
	@cd pas-platform && printf 'Batch readiness:  http://localhost:%s/actuator/health/readiness\n' "$$(docker compose port pas-batch 8081 | sed 's/.*://')"

dev-down:
	cd pas-platform && docker compose down

dev-clean:
	cd pas-platform && docker compose down -v

local:
	$(MAKE) test
	$(MAKE) dev
	@cd pas-platform && \
	POLICY_PORT="$$(docker compose port pas-policy-service 8080 | sed 's/.*://')" && \
	BATCH_PORT="$$(docker compose port pas-batch 8081 | sed 's/.*://')" && \
	curl -fsS "http://localhost:$$POLICY_PORT/actuator/health/readiness" && \
	curl -fsS "http://localhost:$$BATCH_PORT/actuator/health/readiness"

extract-compare:
	@if [ -z "$(EXPECTED)" ] || [ -z "$(ACTUAL)" ]; then \
		printf 'Usage: make extract-compare EXPECTED=<dir> ACTUAL=<dir>\n' >&2; exit 2; \
	fi
	@if ! ls pas-platform/pas-batch/target/pas-batch-*.jar >/dev/null 2>&1; then \
		cd pas-platform && ./mvnw -B -q -pl pas-batch -am package -DskipTests; \
	fi
	cd pas-platform && jar=$$(find pas-batch/target -maxdepth 1 -type f -name 'pas-batch-*.jar' ! -name '*-original.jar' | head -n 1) && \
		java -Dloader.main=com.acme.insurance.pas.batch.extract.compare.ExtractCompare \
		-cp "$$jar" org.springframework.boot.loader.launch.PropertiesLauncher \
		"$(abspath $(EXPECTED))" "$(abspath $(ACTUAL))"

load-premium-batch:
	cd pas-platform && ./mvnw -B -ntp -pl pas-batch -am \
		-Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
		-Dfailsafe.failIfNoSpecifiedTests=false \
		-Dit.test=PremiumJobLoadIT -Dpas.load.policies=$(POLICIES) \
		-Dpas.batch.it.excluded-groups= \
		-Dspotless.check.skip=true -Dspotbugs.skip=true verify

rollback-rehearsal: dev-env
	cd pas-platform && set -a && . ./.env && set +a && ./dev/rollback-rehearsal.sh
