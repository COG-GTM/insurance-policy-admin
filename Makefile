.PHONY: compat compat-static compat-api compat-build parity parity-cobol parity-golden parity-java

compat: compat-static compat-build compat-api

compat-static:
	python3 compat/check_schema.py
	python3 compat/check_layouts.py
	python3 compat/check_security.py

compat-build:
	./compat/build_modules.sh

compat-api:
	./compat/api/run_facade.sh

parity: parity-cobol parity-java

parity-cobol:
	python3 -m unittest discover -s parity/tools -p 'test_*.py' && python3 parity/tools/cobol_harness.py check

parity-golden:
	python3 parity/tools/cobol_harness.py update

parity-java:
	cd pas-platform && ./mvnw -B -ntp -pl pas-policy-service -am -Dtest='*Parity*Test,*ParityHarness*Test' -Dsurefire.failIfNoSpecifiedTests=false test
