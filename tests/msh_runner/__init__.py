"""Marathon Shepherd test runner.

Entry point: ``tests/run_tests.py`` (via ``tests/run_tests.sh``). Module map:

- ``options``      command-line model and parser
- ``stages``       stable stage ids and their display labels
- ``runner``       TestRunner, composed of the mixins below
- ``stage_state``  status rows and transitions
- ``preflight``    configuration and stage-aware prerequisite checks
- ``execution``    stage selection and process execution
- ``reporting``    summaries, failure excerpts and ``--scan``
- ``printers``     plain and Rich output backends
- ``logs``         stage log markers and scenario tallies
- ``environment``  host probes (docker, adb, emulator, HTTP)
- ``gradle``       Gradle command-line assembly
- ``status``       status labels and timing
- ``settings``     repository root and display tunables
"""
