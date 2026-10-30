.PHONY: compat compat-static compat-api compat-build

compat: compat-static compat-build compat-api

compat-static:
	python3 compat/check_schema.py
	python3 compat/check_layouts.py
	python3 compat/check_security.py
	python3 -m unittest discover -s compat/tests

compat-build:
	./compat/build_modules.sh

compat-api:
	./compat/api/run_facade.sh
