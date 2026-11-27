"""Validate the optional Java/C# snapshot-schema exchange with python-jsonschema."""

import argparse
from copy import deepcopy
from decimal import Decimal
import json
from pathlib import Path

from jsonschema import Draft7Validator


def read(directory, author, case, artifact):
    path = directory / f"{author}-{case}-{artifact}.json"
    with path.open(encoding="utf-8") as file:
        return json.load(file, parse_float=Decimal)


def reject(validator, document, path, replacement=None, remove=False):
    invalid = deepcopy(document)
    node = invalid
    for part in path[:-1]:
        node = node[part]
    if remove:
        del node[path[-1]]
    else:
        node[path[-1]] = replacement
    if validator.is_valid(invalid):
        raise AssertionError(f"Accepted invalid value at {path!r}")


def nested_errors(errors):
    for error in errors:
        yield error
        yield from nested_errors(error.context)


def verify_stock_alarm_policy(directory):
    samples = read(directory, "java", "stock-alarm-input", "fixtures")
    for author in ("java", "csharp"):
        for profile in ("values", "references"):
            name = f"stock-alarm-{profile}"
            standard = read(directory, author, name + "-default", "schema")
            configured = read(directory, author, name + "-configured", "schema")
            Draft7Validator.check_schema(standard)
            Draft7Validator.check_schema(configured)
            standard_validator = Draft7Validator(standard)
            for sample in samples.values():
                standard_validator.validate(sample)
            validator = Draft7Validator(configured)
            errors = nested_errors(validator.iter_errors(samples["missing"]))
            if not any(error.validator == "required" and "alarm-limit" in error.message for error in errors):
                raise AssertionError(f"{author} {profile} schema failed to require the enabled alarm's limit")
            validator.validate(samples["configured"])
            validator.validate(samples["inactive"])
    print("Eight stock alarm schemas agreed across Java, C#, and both JSON profiles.")


def verify_uuid_snapshots(directory):
    for shape, field in (("generic", "samples"), ("bounded", "ids")):
        for profile in ("values", "references"):
            case = f"uuid-{shape}-{profile}"
            for schema_author in ("java", "csharp"):
                schema = read(directory, schema_author, case, "schema")
                Draft7Validator.check_schema(schema)
                validator = Draft7Validator(schema)
                for author in ("java", "csharp"):
                    document = read(directory, author, case, "snapshot")
                    members = document[field]
                    if shape == "generic":
                        if members != [document["latest"], None, document["latest"]]:
                            raise AssertionError(f"{author} {case} lost nullable or repeated UUIDs")
                    elif len(members) != 2 or members[0] is not None:
                        raise AssertionError(f"{author} {case} lost its null slot")
                    validator.validate(document)
                    reject(validator, document, [field], [])
                    reject(validator, document, [field, 0], 17)
                    reject(validator, document, [field, 0], "1-1-1-1-1")
                    if shape == "bounded":
                        reject(validator, document, [field], [None, None, None])
                        validator.validate({**document, field: [None]})
                    else:
                        reject(validator, document, ["latest"], document["latest"] + "\n")
                        validator.validate({**document, "latest": None})
    print("Draft-07 checked both writers against both UUID schemas in each profile.")


def verify(directory):
    cases = ["inventory", "catalog-values", "catalog-references", "keyed", "collections",
             "archive", "immutable", "defaults", "scalars", "constraints",
             "checked-defaults-values", "checked-defaults-references"]
    negative_cases = {
        "inventory": [(["@vmf-id"], "external"), (["stock_items", 0, "available_qty"], "0")],
        "catalog-values": [(["items", 0, "@vmf-type"], "unregistered"), (["@vmf-id"], "extra"), (["featured"], "1")],
        "catalog-references": [(["items", 0, "@vmf-type"], "instrument-misspelling"), (["featured"], "")],
        "keyed": [(["items", 0, "sku_code"], None), (["assignments", 0, "part"], None)],
        "collections": [(["counts"], [1, 2, 3, 4]), (["levels"], []), (["limits"], {}),
                        (["phases"], ["calibrate", "calibrate"]),
                        (["markers", 0], {"key": 2, "value": "A", "extra": True})],
        "archive": [(["review", 0], ""), (["review"], [None, None]), (["head"], None)],
        "immutable": [(["portfolio", "@vmf-type"], "unregistered")],
        "scalars": [(["byteValue"], 128), (["shortValue"], -32769), (["intValue"], 2147483648),
                    (["longValue"], 9223372036854775808), (["charValue"], "🛠"), (["doubleValue"], "infinity")],
        "constraints": [(["title"], "xx"), (["entries", 0, "status_label"], "no match"),
                        (["entries", 0, "sequence"], 9007199254740992),
                        (["entries", 0, "ratio"], Decimal("0.5")), (["entries", 0, "ratio"], "NaN"),
                        (["entries", 0, "notes"], "abc\n")],
    }
    for case in cases:
        native_schema = read(directory, "csharp", case, "schema")
        java_schema = read(directory, "java", case, "schema")
        Draft7Validator.check_schema(native_schema)
        Draft7Validator.check_schema(java_schema)
        validator = Draft7Validator(native_schema)
        java_validator = Draft7Validator(java_schema)
        for author in ("java", "csharp"):
            document = read(directory, author, case, "snapshot")
            validator.validate(document)
            if case not in {"collections", "scalars"}:
                java_validator.validate(document)
            if case.startswith("checked-defaults-"):
                for current in (validator, java_validator):
                    for field, invalid in (("label", ""), ("limit", 11), ("enabled", 1),
                                           ("primitive", None), ("mandatory", None)):
                        reject(current, document, [field], invalid)
        for path, replacement in negative_cases.get(case, []):
            reject(validator, document, path, replacement)
        if case == "keyed":
            reject(validator, document, ["items", 0, "sku_code"], remove=True)
        if case == "constraints":
            document["entries"][0]["status_label"] = "A-10"
            reject(validator, document, ["entries", 0, "sequence"], remove=True)
    print(f"Draft-07 validated {len(cases)} native schemas and both producers' snapshots, and rejected all invalid variants.")
    verify_stock_alarm_policy(directory)
    verify_uuid_snapshots(directory)
    verify_polymorphic_roots(directory)
    verify_catalog_type_names(directory)
    verify_interface_only_roots(directory)


def verify_catalog_type_names(directory):
    for profile in ("values", "references"):
        case = f"catalog-renamed-{profile}"
        documents = [
            read(directory, author, case, artifact)
            for author, artifact in (("java", "snapshot"), ("java", "canonical"),
                                     ("java", "previous"), ("csharp", "snapshot"))
        ]
        for author in ("java", "csharp"):
            schema = read(directory, author, case, "schema")
            Draft7Validator.check_schema(schema)
            validator = Draft7Validator(schema)
            for document in documents:
                validator.validate(document)
                reject(validator, document, ["items", 0, "@vmf-type"], "unknown-sensor")
                reject(validator, document, ["items", 0, "@vmf-type"], "eu.mihosoft.vmf.example.workshops.Catalog")
                reject(validator, document, ["items", 0, "unit"], {})
            former = deepcopy(documents[0])
            former["items"][0]["@vmf-type"] = "thermometer"
            validator.validate(former)
            for producer in ("java", "csharp"):
                validator.validate(read(directory, producer, case, "untagged"))
    print("Draft-07 accepted current, prior, and qualified nested model names and the declared concrete fallback.")


def verify_interface_only_roots(directory):
    for profile in ("values", "references"):
        for root, incompatible in (("product", "eu.mihosoft.vmf.example.workshops.Catalog"), ("shelf", "instrument")):
            case = f"role-{root}-{profile}"
            for author in ("java", "csharp"):
                schema = read(directory, author, case, "schema")
                Draft7Validator.check_schema(schema)
                validator = Draft7Validator(schema)
                for producer in ("java", "csharp"):
                    document = read(directory, producer, case, "snapshot")
                    validator.validate(document)
                    reject(validator, document, ["@vmf-type"], remove=True)
                    reject(validator, document, ["@vmf-type"], incompatible)
                    reject(validator, document, ["@vmf-type"], "unknown")
    print("Draft-07 verified both role roots require a registered concrete implementation.")


def verify_polymorphic_roots(directory):
    legacy = {"sku": "legacy"}
    unexpected = {"@vmf-type": "not-registered"}
    malformed = {"@vmf-type": "thermometer", "unit": {}}
    for profile in ("values", "references"):
        case = f"instrument-root-{profile}"
        snapshots = [
            read(directory, author, case, artifact)
            for author in ("java", "csharp") for artifact in ("base", "snapshot")
        ]
        for author in ("java", "csharp"):
            schema = read(directory, author, case, "schema")
            Draft7Validator.check_schema(schema)
            validator = Draft7Validator(schema)
            for accepted in (*snapshots, legacy):
                validator.validate(accepted)
            for rejected in (unexpected, malformed):
                if validator.is_valid(rejected):
                    raise AssertionError(f"{author} {profile} schema accepted {rejected}")
    schema = read(directory, "csharp", "instrument-root-plain", "schema")
    Draft7Validator.check_schema(schema)
    plain = Draft7Validator(schema)
    plain.validate(read(directory, "csharp", "instrument-root-plain", "snapshot"))
    for author in ("java", "csharp"):
        if plain.is_valid(read(directory, author, "instrument-root-values", "snapshot")):
            raise AssertionError(f"Strict plain schema accepted the {author} tagged subtype")
    print("Draft-07 checked both producers and both typed root schema profiles, including exact C# plain.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    verify(parser.parse_args().directory)
