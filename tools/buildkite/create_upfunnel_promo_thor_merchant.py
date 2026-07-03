#!/usr/bin/env python3
from __future__ import annotations

import argparse
import asyncio
import json
import os
import shlex
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any


METADATA_PUBLIC_KEY = "upfunnel-sdk-promo-messaging-public-key"
METADATA_MERCHANT_ARI = "upfunnel-sdk-promo-messaging-merchant-ari"
METADATA_EXTERNAL_ID = "upfunnel-sdk-promo-messaging-external-id"
METADATA_EXPECTED_ALA = "upfunnel-sdk-promo-messaging-expected-ala"


def add_all_the_things_paths(all_the_things_dir: Path) -> None:
    e2e_dir = all_the_things_dir / "test_framework" / "tests" / "e2e"
    upfunnel_dir = e2e_dir / "thor" / "upfunnel_messaging"
    api_dir = all_the_things_dir / "test_framework" / "api"
    chameleon_toolkit_dir = all_the_things_dir / "chameleon" / "toolkit"
    for path in (str(upfunnel_dir), str(e2e_dir), str(api_dir), str(chameleon_toolkit_dir)):
        if path not in sys.path:
            sys.path.insert(0, path)


def set_buildkite_metadata(key: str, value: str) -> None:
    if not shutil.which("buildkite-agent"):
        return

    subprocess.run(
        ["buildkite-agent", "meta-data", "set", key, value],
        check=True,
    )


def write_env_file(path: Path, values: dict[str, str]) -> None:
    lines = [f"{key}={shlex.quote(value)}" for key, value in sorted(values.items())]
    path.write_text("\n".join(lines) + "\n")


async def wait_for_live_upfunnel_promo(
    session_io: Any,
    public_key: str,
    params: dict[str, str],
    path: str,
    max_attempts: int = 60,
    delay_seconds: int = 2,
) -> dict[str, Any]:
    last_status = "(no response)"
    last_body = "(no body)"
    last_error = ""

    for attempt in range(1, max_attempts + 1):
        if attempt > 1:
            await asyncio.sleep(delay_seconds)

        try:
            response = await session_io.http.get(f"{path}{public_key}", params=params)
            last_status = str(response.status)
            try:
                json_response = await response.json()
            except Exception as exc:
                last_error = repr(exc)
                last_body = (await response.text())[:2000]
            else:
                last_body = json.dumps(json_response, sort_keys=True)[:2000]
                promo = json_response.get("promo") if isinstance(json_response, dict) else None
                if response.status == 200 and isinstance(promo, dict) and promo.get("ala"):
                    return json_response
        except Exception as exc:
            last_error = repr(exc)

        if attempt == 1 or attempt % 10 == 0:
            print(
                "Waiting for live Upfunnel promo response "
                f"(attempt {attempt}/{max_attempts}, status={last_status}, error={last_error or 'none'})"
            )

    raise AssertionError(
        "Promo data not found after max attempts. "
        f"last_status={last_status}, last_error={last_error or 'none'}, last_body={last_body!r}"
    )


async def create_thor_merchant(backend_url: str) -> dict[str, str]:
    from affirm.test_framework.api.utils.session import SessionIO
    from defs import EXTERNAL_ID_PARAM, EXTERNAL_ID_VALUE, PROMO_ALA_REQUEST_PARAMS, PROMO_PATH
    from financing_program_util import FinancingProgramType
    from merchant_util import setup_merchant_and_pricing_bundles
    from template_util import CUSTOM_CTA, CUSTOM_INSTALLMENT_TAGLINE, create_external_id_templates

    expected_ala = f"{CUSTOM_INSTALLMENT_TAGLINE}. {CUSTOM_CTA}"

    async with SessionIO.create(backend_url=backend_url) as session_io:
        public_key, merchant_ari = await setup_merchant_and_pricing_bundles(
            session_io,
            [FinancingProgramType.classic, FinancingProgramType.split_pay],
            prequal_enabled=True,
        )
        await create_external_id_templates(session_io, merchant_ari, EXTERNAL_ID_VALUE)
        session_io.set_axp_override("add_button_role_to_cta_ff", "feature_off")

        ala_request = PROMO_ALA_REQUEST_PARAMS.copy()
        ala_request[EXTERNAL_ID_PARAM] = EXTERNAL_ID_VALUE
        upfunnel_response = await wait_for_live_upfunnel_promo(
            session_io,
            public_key,
            ala_request,
            PROMO_PATH,
        )
        actual_ala = upfunnel_response["promo"]["ala"]
        if actual_ala != expected_ala:
            raise AssertionError(
                "Unexpected live Upfunnel ALA response: "
                f"expected {expected_ala!r}, got {actual_ala!r}"
            )

    return {
        "AFFIRM_PUBLIC_KEY": public_key,
        "AFFIRM_PROMO_EXTERNAL_ID": EXTERNAL_ID_VALUE,
        "AFFIRM_EXPECTED_PROMO_TEXT": expected_ala,
        "UPFUNNEL_MERCHANT_ARI": merchant_ari,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create a real Thor merchant for SDK promo tests and verify live Upfunnel output."
    )
    parser.add_argument(
        "--all-the-things-dir",
        default=os.environ.get("ALL_THE_THINGS_DIR", "/workspace/all-the-things"),
        help="Path to an all-the-things checkout with test_framework available.",
    )
    parser.add_argument(
        "--backend-url",
        default=os.environ.get("BACKEND_URL"),
        required=os.environ.get("BACKEND_URL") is None,
        help="Thor backend URL, for example https://<thor-id>.affirm-thor.com.",
    )
    parser.add_argument(
        "--output-env",
        default=os.environ.get("UPFUNNEL_PROMO_THOR_ENV", "upfunnel-promo-thor.env"),
        help="File path to write shell-compatible Thor merchant environment variables.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    add_all_the_things_paths(Path(args.all_the_things_dir).resolve())
    values = asyncio.run(create_thor_merchant(args.backend_url))

    output_env = Path(args.output_env)
    write_env_file(output_env, values)

    set_buildkite_metadata(METADATA_PUBLIC_KEY, values["AFFIRM_PUBLIC_KEY"])
    set_buildkite_metadata(METADATA_MERCHANT_ARI, values["UPFUNNEL_MERCHANT_ARI"])
    set_buildkite_metadata(METADATA_EXTERNAL_ID, values["AFFIRM_PROMO_EXTERNAL_ID"])
    set_buildkite_metadata(METADATA_EXPECTED_ALA, values["AFFIRM_EXPECTED_PROMO_TEXT"])

    print(json.dumps({**values, "env_file": str(output_env)}, sort_keys=True))


if __name__ == "__main__":
    main()
