.PHONY: compat compat-static compat-api compat-build inventory adr

compat: compat-static compat-build compat-api

compat-static:
	python3 compat/check_schema.py
	python3 compat/check_layouts.py
	python3 compat/check_security.py

compat-build:
	./compat/build_modules.sh

compat-api:
	./compat/api/run_facade.sh

inventory:
	python3 tools/discovery/pas_inventory.py
	python3 -m unittest discover -s tools/discovery -p 'test_*.py'

adr:
	python3 tools/adr/check_adr.py
	python3 -m unittest discover -s tools/adr -p 'test_*.py'
