# Broker Portal migration: DB2 linked server → PAS inquiry API (AB-528)

The Broker Portal (SQL Server) reads PAS data from DB2 `DBPD` through a SQL Server linked server
over ODBC ([I-03](integrations.md#i-03)). DB2 retires with the mainframe, so the portal must read
through `pas-policy-service` instead. Re-platforming the portal is out of scope (epic AB-516).

**Status:** engineering done; **Pending: Broker Portal team** (agreement on the approach and the
query list below), **Pending: Security** (client registration and scopes),
**Pending: ARB** (see `docs/adr/0004-legacy-integration-retirement.md`).

## Decision: inquiry API, not a read replica

| Option | Why / why not |
|--------|---------------|
| **AB-524 inquiry API** (chosen) | Authenticated (OIDC JWT), scoped (`policy:read`, `policy:pii:read`) and object-authorised (agent/branch/book claims; not entitled = 404), audited, PII masked by default, versioned by OpenAPI (`pas-policy-v1.yaml`, drift-tested). ADR 0001 D5 already says the portal must use `pas-policy-service`. |
| PostgreSQL read replica + SQL Server linked server (PostgreSQL ODBC) | Smallest change on the portal side (re-point the linked server), but it exposes the `pas` schema as an integration contract (every table change becomes a portal break), bypasses entitlement checks and PII masking (holder PII is encrypted at rest with application keys, AB-521, so the portal could not read it anyway), needs a database credential on the portal host and a new network path into the database tier. Rejected; can be revisited with the portal team if the API latency is not acceptable. |

## Query-by-query mapping

The linked-server definition and the portal's SQL are not in the repository
([M-08](missing-artifacts.md#m-08), [OQ-14](open-questions.md#oq-14)). The list below is
**inferred** from the data the portal displays per the README and from the facade's read
queries (`java-facade` `PolicyRepository`), which served the same screens. The portal team must
confirm it (Pending: Broker Portal team); any extra query gets an endpoint in the same style.

`BrokerPortalApiIT` reads the **Example request** column of this table and runs every request
against `pas-policy-service` with PostgreSQL only (no DB2 driver on the classpath, no DB2 URL):
every row must answer 200.

| ID | Linked-server SQL (inferred, DB2 `ACMEINS` via `DBPD`) | API call | Scope | Example request |
|----|-----------------------------------------------------|----------|-------|-----------------|
| PQ-01 | `SELECT ... FROM DBPD..ACMEINS.POLICIES WHERE POLICY_NUMBER = ?` | `GET /api/v1/policies/{policyNumber}` | `policy:read` | `GET /api/v1/policies/POL-00000001` |
| PQ-02 | `SELECT ... FROM DBPD..ACMEINS.COVERAGES WHERE POLICY_NUMBER = ? ORDER BY SEQUENCE_NUM` | `GET /api/v1/policies/{policyNumber}/coverages` | `policy:read` | `GET /api/v1/policies/POL-00000001/coverages` |
| PQ-03 | `SELECT ... FROM DBPD..ACMEINS.POLICY_HOLDERS h JOIN ...POLICIES p ON h.CUST_ID = p.POLICYHOLDER_ID WHERE p.POLICY_NUMBER = ?` | `GET /api/v1/policies/{policyNumber}/holder` | `policy:read` (+ `policy:pii:read` to unmask) | `GET /api/v1/policies/POL-00000001/holder` |
| PQ-04 | `SELECT ... FROM DBPD..ACMEINS.POLICIES WHERE AGENT_CODE = ? ORDER BY POLICY_NUMBER` | `GET /api/v1/policies?agentCode=` (new, AB-528) | `policy:read` | `GET /api/v1/policies?agentCode=AG1001` |
| PQ-05 | `SELECT ... FROM DBPD..ACMEINS.POLICIES WHERE BRANCH_CODE = ? AND POLICY_STATUS = ?` | `GET /api/v1/policies?branchCode=&status=` (new, AB-528) | `policy:read` | `GET /api/v1/policies?branchCode=CHI1&status=AC` |
| PQ-06 | `SELECT ... FROM DBPD..ACMEINS.PREMIUMS WHERE POLICY_NUMBER = ? ORDER BY TERM_EFFECTIVE_DATE DESC` | `GET /api/v1/policies/{policyNumber}/premiums` (new, AB-528) | `policy:read` | `GET /api/v1/policies/POL-00000001/premiums` |

Notes:

- The book listing (`GET /api/v1/policies`) needs `agentCode` and/or `branchCode`; `page`
  (from 0) and `size` (1–200, default 50) page through it in policy-number order. Codes are
  case-insensitive. Rows the caller is not entitled to are left out (never 403) before paging, so
  a broker sees only their own book and an empty page means the end of it.
- Everything else follows AB-524: no token → 401, missing scope → 403, not entitled → 404.
- Dates are ISO `yyyy-MM-dd`; codes are trimmed (DB2 `CHAR` padding is not carried).

## Portal-side client example

The portal authenticates as an OIDC client (client-credentials) whose token carries
`policy:read` and a `book` claim for the brokers it serves (or the portal passes through the
signed-in broker's token with `agent_code`). Example (C#, the portal's stack is .NET/SQL Server):

```csharp
using System.Net.Http.Headers;
using System.Net.Http.Json;

public sealed class PasPolicyClient(HttpClient http, ITokenProvider tokens)
{
    // http.BaseAddress = https://pas-api.<env>.acme.internal/  (gateway route, ADR 0001)
    public async Task<PolicyView?> GetPolicyAsync(string policyNumber, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, $"api/v1/policies/{Uri.EscapeDataString(policyNumber)}");
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", await tokens.GetAsync("policy:read", ct));
        using var res = await http.SendAsync(req, ct);
        if (res.StatusCode == System.Net.HttpStatusCode.NotFound) return null; // missing or not entitled
        res.EnsureSuccessStatusCode();
        return await res.Content.ReadFromJsonAsync<PolicyView>(cancellationToken: ct);
    }

    public Task<List<PolicyView>?> GetAgentBookAsync(string agentCode, int page, CancellationToken ct) =>
        GetJsonAsync<List<PolicyView>>($"api/v1/policies?agentCode={Uri.EscapeDataString(agentCode)}&page={page}&size=50", ct);

    private async Task<T?> GetJsonAsync<T>(string path, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, path);
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", await tokens.GetAsync("policy:read", ct));
        using var res = await http.SendAsync(req, ct);
        res.EnsureSuccessStatusCode();
        return await res.Content.ReadFromJsonAsync<T>(cancellationToken: ct);
    }
}
```

Where the portal's own SQL Server views join PAS data with portal tables, the portal team
replaces the linked-server view with a cache table refreshed from these endpoints (or calls the
API at read time). That change is on the portal side: Pending: Broker Portal team.

## Cut-over

1. Register the portal client and its `book` claims (Pending: Security).
2. Portal switches each query PQ-01..PQ-06 to the API behind its own feature flag; compare
   results against the linked server during the parallel run (AB-529).
3. Drop the linked server `DBPD`, its ODBC DSN and DB2 auth id (listed in
   [queue-decommission.md](queue-decommission.md); Pending: Mainframe team / DBA).
