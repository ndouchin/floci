"""Tests for regen_eks_addon_versions.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import json
import subprocess
import sys

import regen_eks_addon_versions as r


JAVA = '''
    private static final Map<String, String> K3S_IMAGES = Map.of(
            "1.29", "rancher/k3s:v1.29.15-k3s1",
            "1.30", "rancher/k3s:v1.30.10-k3s1",
            "1.31", "rancher/k3s:v1.31.5-k3s1"
    );
    String unrelated = "1.99";
'''


def response(cluster_version: str, *versions: tuple[str, bool]) -> dict:
    return {"addons": [{
        "addonName": "coredns", "type": "networking", "owner": "aws", "publisher": "eks",
        "marketplaceInformation": {"productId": "ignored"},
        "addonVersions": [{
            "addonVersion": version, "architecture": ["amd64", "arm64"], "computeTypes": ["ec2"],
            "compatibilities": [{"clusterVersion": cluster_version, "platformVersions": ["*"],
                                 "defaultVersion": default}],
            "requiresConfiguration": False, "requiresIamPermissions": False,
        } for version, default in versions],
    }]}


def test_kubernetes_versions_come_from_the_k3s_image_map_newest_first():
    assert r.kubernetes_versions(JAVA) == ["1.31", "1.30", "1.29"]


def test_merge_joins_the_per_version_compatibilities_of_one_release():
    merged = r.merge("coredns", {
        "1.31": response("1.31", ("v1.11.4-eksbuild.2", True), ("v1.11.4-eksbuild.10", False)),
        "1.30": response("1.30", ("v1.11.4-eksbuild.2", False), ("v1.11.1-eksbuild.9", True)),
    })
    assert [v["addonVersion"] for v in merged["addonVersions"]] == [
        "v1.11.4-eksbuild.10", "v1.11.4-eksbuild.2", "v1.11.1-eksbuild.9"]
    shared = merged["addonVersions"][1]
    assert shared["compatibilities"] == [
        {"clusterVersion": "1.31", "platformVersions": ["*"], "defaultVersion": True},
        {"clusterVersion": "1.30", "platformVersions": ["*"], "defaultVersion": False},
    ]
    assert set(merged) == {"addonName", "type", "owner", "publisher", "addonVersions"}
    assert set(shared) == set(r.VERSION_FIELDS)


def test_merge_ignores_compatibilities_for_versions_that_were_not_asked_for():
    stray = response("1.31", ("v1.11.4-eksbuild.2", True))
    stray["addons"][0]["addonVersions"][0]["compatibilities"].append(
        {"clusterVersion": "1.27", "platformVersions": ["*"], "defaultVersion": True})
    merged = r.merge("coredns", {"1.31": stray})
    assert [c["clusterVersion"] for c in merged["addonVersions"][0]["compatibilities"]] == ["1.31"]


def test_validate_requires_exactly_one_default_per_addon_and_cluster_version():
    catalog = {"addons": [r.merge("coredns", {
        "1.31": response("1.31", ("v2", True), ("v1", True)),
        "1.30": response("1.30", ("v1", False)),
    })]}
    assert r.validate(catalog, ["coredns"], ["1.31", "1.30"]) == [
        "coredns: 2 default versions for Kubernetes 1.31",
        "coredns: 0 default versions for Kubernetes 1.30",
    ]


def test_validate_does_not_count_a_default_of_another_cluster_version():
    catalog = {"addons": [r.merge("coredns", {
        "1.31": response("1.31", ("v2", True)),
        "1.30": response("1.30", ("v2", False), ("v1", False)),
    })]}
    assert r.validate(catalog, ["coredns"], ["1.31", "1.30"]) == [
        "coredns: 0 default versions for Kubernetes 1.30"]


def test_validate_requires_releases_newest_first():
    catalog = {"addons": [r.merge("coredns", {"1.31": response("1.31", ("v2", True), ("v1", False))})]}
    catalog["addons"][0]["addonVersions"].reverse()
    assert r.validate(catalog, ["coredns"], ["1.31"]) == ["coredns: releases are not unique and newest first"]


def test_validate_reports_missing_and_unlisted_addons():
    catalog = {"addons": [r.merge("coredns", {"1.31": response("1.31", ("v1", True))})]}
    assert r.validate(catalog, ["vpc-cni"], ["1.31"]) == [
        "vpc-cni: missing", "add-ons not in ADDONS: ['coredns']"]


def test_validate_refuses_values_java_cannot_read():
    catalog = {"addons": [r.merge("coredns", {"1.31": response("1.31", ("v2", True), ("v1", False))})]}
    newest, oldest = catalog["addons"][0]["addonVersions"]
    newest["compatibilities"][0]["defaultVersion"] = "false"
    oldest["architecture"] = "amd64"
    assert r.validate(catalog, ["coredns"], ["1.31"]) == [
        "coredns v2: wrong compatibility types ['defaultVersion']",
        "coredns v1: wrong types ['architecture']",
    ]


def test_validate_refuses_an_addon_whose_versions_are_not_a_list():
    catalog = {"addons": [r.merge("coredns", {"1.31": response("1.31", ("v1", True))})]}
    catalog["addons"][0]["addonVersions"] = {}
    assert r.validate(catalog, ["coredns"], ["1.31"]) == ["coredns: wrong types ['addonVersions']"]


def test_render_round_trips_one_version_per_line():
    catalog = {"addons": [r.merge("coredns", {"1.31": response("1.31", ("v2", True), ("v1", False))})]}
    text = r.render(catalog)
    assert json.loads(text) == catalog
    assert text.count("\n") == 6


def test_the_vendored_catalog_covers_every_addon_and_cluster_version():
    catalog = json.loads(r.OUTPUT.read_text())
    assert r.validate(catalog, r.ADDONS, r.kubernetes_versions(r.CLUSTER_MANAGER.read_text())) == []
    assert r.render(catalog) == r.OUTPUT.read_text()


def test_a_failed_sync_reports_aws_and_leaves_the_file_unchanged(tmp_path, monkeypatch, capsys):
    output = tmp_path / "addon-versions.json"
    output.write_text("vendored\n")
    monkeypatch.setattr(r, "OUTPUT", output)
    monkeypatch.setattr(sys, "argv", ["regen_eks_addon_versions.py"])

    def expired(addon_name, kubernetes_version):
        raise subprocess.CalledProcessError(254, ["aws"], stderr="The security token included in the request is expired\n")
    monkeypatch.setattr(r, "describe", expired)

    assert r.main() == 1
    assert output.read_text() == "vendored\n"
    assert "security token included in the request is expired" in capsys.readouterr().err


def test_a_saved_answer_is_merged_like_the_filtered_calls_and_keeps_the_other_addons(tmp_path, monkeypatch):
    output = tmp_path / "addon-versions.json"
    vendored = {"addons": [r.merge("coredns", {"1.31": response("1.31", ("v1", True)),
                                               "1.30": response("1.30", ("v1", True))})]}
    output.write_text(r.render(vendored))
    saved = response("1.31", ("v0.8.1-eksbuild.2", True), ("v0.8.0-eksbuild.1", False))["addons"][0]
    saved["addonName"] = "metrics-server"
    saved["addonVersions"][0]["compatibilities"].append(
        {"clusterVersion": "1.30", "platformVersions": ["*"], "defaultVersion": True})
    saved["addonVersions"][1]["compatibilities"][0]["clusterVersion"] = "1.27"
    answer = tmp_path / "metrics-server.json"
    answer.write_text(json.dumps(saved))
    monkeypatch.setattr(r, "OUTPUT", output)
    monkeypatch.setattr(r, "ADDONS", ["coredns", "metrics-server"])
    monkeypatch.setattr(r, "kubernetes_versions", lambda source: ["1.31", "1.30"])
    monkeypatch.setattr(sys, "argv", ["regen_eks_addon_versions.py", "--response", str(answer)])

    def no_aws(addon_name, kubernetes_version):
        raise AssertionError("a saved answer must not call AWS")
    monkeypatch.setattr(r, "describe", no_aws)

    catalog = r.merge_saved(vendored, [saved], ["1.31", "1.30"])
    assert [entry["addonName"] for entry in catalog["addons"]] == ["coredns", "metrics-server"]
    assert catalog["addons"][0] == vendored["addons"][0]
    assert [v["addonVersion"] for v in catalog["addons"][1]["addonVersions"]] == ["v0.8.1-eksbuild.2"]
    assert [c["clusterVersion"] for c in catalog["addons"][1]["addonVersions"][0]["compatibilities"]] == ["1.31", "1.30"]
    assert set(catalog["addons"][1]) == {"addonName", "type", "owner", "publisher", "addonVersions"}
    assert r.main() == 0
    assert json.loads(output.read_text()) == catalog


def test_a_saved_answer_for_an_unlisted_addon_is_refused(tmp_path):
    answer = tmp_path / "other.json"
    answer.write_text(json.dumps({"addons": [{"addonName": "kube-state-metrics", "addonVersions": []}]}))
    try:
        r.saved_addons(answer)
    except ValueError as error:
        assert "not in ADDONS" in str(error)
    else:
        raise AssertionError("an add-on outside ADDONS must be refused")
