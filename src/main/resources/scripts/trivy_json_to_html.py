import json
import html
import sys
from collections import Counter

if len(sys.argv) < 2:
    print("Usage: python3 trivy_json_to_html.py <trivy-json-file> [output-html-file]")
    sys.exit(1)

input_file = sys.argv[1]
output_file = sys.argv[2] if len(sys.argv) > 2 else "trivy-report.html"

severity_order = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN"]

with open(input_file, "r", encoding="utf-8") as f:
    data = json.load(f)

artifact_name = data.get("ArtifactName", "Unknown")
artifact_type = data.get("ArtifactType", "Unknown")
trivy_version = data.get("Trivy", {}).get("Version", "Unknown")

metadata = data.get("Metadata", {})
os_info = metadata.get("OS", {})
os_name = f"{os_info.get('Family', '')} {os_info.get('Name', '')}".strip()
eosl = os_info.get("EOSL", "Unknown")

all_vulnerabilities = []

for result in data.get("Results", []):
    target = result.get("Target", "Unknown Target")
    result_class = result.get("Class", "")
    result_type = result.get("Type", "")

    for vuln in result.get("Vulnerabilities", []) or []:
        all_vulnerabilities.append({
            "Target": target,
            "Class": result_class,
            "Type": result_type,
            "Severity": vuln.get("Severity", "UNKNOWN"),
            "VulnerabilityID": vuln.get("VulnerabilityID", ""),
            "PkgName": vuln.get("PkgName", ""),
            "InstalledVersion": vuln.get("InstalledVersion", ""),
            "FixedVersion": vuln.get("FixedVersion", "No fixed version"),
            "Title": vuln.get("Title", ""),
            "Description": vuln.get("Description", ""),
            "PrimaryURL": vuln.get("PrimaryURL", "")
        })

severity_count = Counter(v["Severity"] for v in all_vulnerabilities)

def esc(value):
    return html.escape(str(value if value is not None else ""))

def badge_class(severity):
    return {
        "CRITICAL": "critical",
        "HIGH": "high",
        "MEDIUM": "medium",
        "LOW": "low",
        "UNKNOWN": "unknown"
    }.get(severity, "unknown")

summary_cards = ""

for severity in severity_order:
    summary_cards += f"""
    <div class="card {badge_class(severity)}">
        <div class="severity-name">{severity}</div>
        <div class="severity-count">{severity_count.get(severity, 0)}</div>
    </div>
    """

rows = ""

severity_rank = {
    "CRITICAL": 1,
    "HIGH": 2,
    "MEDIUM": 3,
    "LOW": 4,
    "UNKNOWN": 5
}

all_vulnerabilities.sort(
    key=lambda x: (
        severity_rank.get(x["Severity"], 99),
        x["PkgName"],
        x["VulnerabilityID"]
    )
)

for vuln in all_vulnerabilities:
    url_html = ""
    if vuln["PrimaryURL"]:
        url_html = f'<a href="{esc(vuln["PrimaryURL"])}" target="_blank">Open</a>'

    rows += f"""
    <tr>
        <td><span class="badge {badge_class(vuln["Severity"])}">{esc(vuln["Severity"])}</span></td>
        <td>{esc(vuln["VulnerabilityID"])}</td>
        <td>{esc(vuln["PkgName"])}</td>
        <td>{esc(vuln["InstalledVersion"])}</td>
        <td>{esc(vuln["FixedVersion"])}</td>
        <td>{esc(vuln["Target"])}</td>
        <td>{esc(vuln["Title"])}</td>
        <td>{url_html}</td>
    </tr>
    """

if not rows:
    rows = """
    <tr>
        <td colspan="8" class="no-data">
            No vulnerabilities found in Results[].Vulnerabilities[].
            Check whether Trivy was run with --scanners vuln.
        </td>
    </tr>
    """

html_content = f"""
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Trivy Vulnerability Report</title>
    <style>
        body {{
            font-family: Arial, sans-serif;
            background: #f4f6f8;
            margin: 0;
            padding: 20px;
            color: #222;
        }}

        h1 {{
            margin-bottom: 5px;
        }}

        .meta {{
            background: white;
            padding: 15px;
            border-radius: 8px;
            margin-bottom: 20px;
            border: 1px solid #ddd;
        }}

        .summary {{
            display: flex;
            gap: 15px;
            flex-wrap: wrap;
            margin-bottom: 25px;
        }}

        .card {{
            background: white;
            border-radius: 8px;
            padding: 15px;
            width: 150px;
            border-left: 8px solid gray;
            box-shadow: 0 1px 4px rgba(0,0,0,0.1);
        }}

        .severity-name {{
            font-weight: bold;
            font-size: 14px;
        }}

        .severity-count {{
            font-size: 32px;
            font-weight: bold;
            margin-top: 8px;
        }}

        .critical {{
            border-left-color: #7b0000;
        }}

        .high {{
            border-left-color: #d32f2f;
        }}

        .medium {{
            border-left-color: #f57c00;
        }}

        .low {{
            border-left-color: #1976d2;
        }}

        .unknown {{
            border-left-color: #616161;
        }}

        table {{
            width: 100%;
            border-collapse: collapse;
            background: white;
            border: 1px solid #ddd;
        }}

        th, td {{
            padding: 10px;
            border: 1px solid #ddd;
            text-align: left;
            vertical-align: top;
            font-size: 13px;
        }}

        th {{
            background: #263238;
            color: white;
            position: sticky;
            top: 0;
        }}

        .badge {{
            color: white;
            padding: 4px 8px;
            border-radius: 12px;
            font-size: 12px;
            font-weight: bold;
            display: inline-block;
        }}

        .badge.critical {{
            background: #7b0000;
        }}

        .badge.high {{
            background: #d32f2f;
        }}

        .badge.medium {{
            background: #f57c00;
        }}

        .badge.low {{
            background: #1976d2;
        }}

        .badge.unknown {{
            background: #616161;
        }}

        .note {{
            background: #fff8e1;
            border: 1px solid #ffecb3;
            padding: 12px;
            border-radius: 8px;
            margin-bottom: 20px;
        }}

        .no-data {{
            text-align: center;
            font-weight: bold;
            color: #d32f2f;
        }}

        a {{
            color: #1565c0;
            font-weight: bold;
        }}
    </style>
</head>
<body>

<h1>Trivy Vulnerability Report</h1>

<div class="meta">
    <b>Artifact Name:</b> {esc(artifact_name)}<br>
    <b>Artifact Type:</b> {esc(artifact_type)}<br>
    <b>Trivy Version:</b> {esc(trivy_version)}<br>
    <b>OS:</b> {esc(os_name)}<br>
    <b>OS End of Life:</b> {esc(eosl)}<br>
    <b>Total Vulnerabilities:</b> {len(all_vulnerabilities)}
</div>

<div class="note">
    This report reads vulnerabilities from <b>Results → Vulnerabilities</b>.
    Do not read from <b>Results → Packages</b>, because packages only show installed package information.
</div>

<h2>Severity Summary</h2>
<div class="summary">
    {summary_cards}
</div>

<h2>Vulnerability Details</h2>

<table>
    <thead>
        <tr>
            <th>Severity</th>
            <th>CVE ID</th>
            <th>Package</th>
            <th>Installed Version</th>
            <th>Fixed Version</th>
            <th>Target</th>
            <th>Title</th>
            <th>URL</th>
        </tr>
    </thead>
    <tbody>
        {rows}
    </tbody>
</table>

</body>
</html>
"""

with open(output_file, "w", encoding="utf-8") as f:
    f.write(html_content)

# print("HTML report generated successfully")
# print("Input JSON :", input_file)
# print("Output HTML:", output_file)
# print("Total vulnerabilities:", len(all_vulnerabilities))

# for severity in severity_order:
#     print(f"{severity}: {severity_count.get(severity, 0)}")