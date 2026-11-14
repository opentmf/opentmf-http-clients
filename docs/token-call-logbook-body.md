# Token call in the Logbook TRACE line: the response body (measured 2026-11-14)

Row OTMF-HTTP-TOKEN-CALL-LOG-WITHOUT-RESPONSE-BODY-1 (ledger #4158). With Logbook on the classpath
and `logging-enabled` (the default), a bearer client's token call is logged like any other call.
Logbook's default body filters mask the tokens of a JSON answer and the `client_secret`,
`password` and `refresh_token` fields of a form request; they do not mask other fields of a
JSON answer, so an identity provider that echoes the request form puts the client secret into the
line.

## How it was measured

- **2.5.1, released:** a scratch Maven project on the released `opentmf-http-clients-starter-rest`
  / `-starter-reactive` 2.5.1 jars, with `logbook-spring-boot-autoconfigure` 4.2.0 defaults and
  `org.zalando.logbook` at TRACE.
- **2.5.2:** `TokenCallLogbookIT` on the branch.
- **Clients:** `jdk`, `apache` and `netty`.
- **Identity provider:** a stand-in whose own logging was off, answering a `400` and a `200`
  that both echo `client_secret`.
- **Results:** the same on all three client types. The lines below are the `jdk` ones; only the
  formatting of the timestamps and correlation ids differs between runs.

## 2.5.1 (released): the echo is in the line

`400`:

```
{"origin":"remote","type":"response","correlation":"c64791bf7c47101b","duration":40,"protocol":"HTTP/1.1","status":400,"headers":{"connection":["keep-alive"],"content-length":["107"],"content-type":["application/json"],"retry-after":["1"],"x-probe":["kept"]},"body":{"error":"invalid_client","error_description":"Invalid client credentials","client_secret":"ECHOED-SECRET"}}
```

`200` (Logbook masks `access_token`, not the echo):

```
{"origin":"remote","type":"response","correlation":"fb7275ba0f3780f4","duration":14,"protocol":"HTTP/1.1","status":200,"headers":{"connection":["keep-alive"],"content-length":["72"],"content-type":["application/json"],"retry-after":["1"],"x-probe":["kept"]},"body":{"access_token":"XXX","expires_in":3600,"client_secret":"ECHOED-SECRET"}}
```

## 2.5.2: the marker stands in for the body

`400`:

```
{"origin":"remote","type":"response","correlation":"eeb942344e525666","duration":50,"protocol":"HTTP/1.1","status":400,"headers":{"connection":["keep-alive"],"content-length":["68"],"content-type":["application/json"],"x-idp-trace":["idp-header-kept"]},"body":"<token response body withheld; bearer-auth.log-response-body: true logs it>"}
```

`200`:

```
{"origin":"remote","type":"response","correlation":"c554abde574a0750","duration":23,"protocol":"HTTP/1.1","status":200,"headers":{"connection":["keep-alive"],"content-length":["125"],"content-type":["application/json"],"x-idp-trace":["idp-header-kept"]},"body":"<token response body withheld; bearer-auth.log-response-body: true logs it>"}
```

The status and headers are logged as before.

The request line is unchanged. Here the client secret is sent in the form, and Logbook's default
filter masks it:

```
{"origin":"local","type":"request","correlation":"eeb942344e525666","protocol":"HTTP/1.1","remote":"localhost","method":"POST","uri":"http://localhost:43521/token","host":"localhost","path":"/token","scheme":"http","port":"43521","headers":{"Accept-Encoding":["gzip"],"Content-Length":["84"],"Content-Type":["application/x-www-form-urlencoded"]},"body":"client_secret=XXX&grant_type=client_credentials&client_id=client"}
```

## Notes

- **Scope:** the change covers only the token calls. The client's own calls keep their bodies, as
  the IT asserts.
- **Debugging:** `bearer-auth.log-response-body: true` restores the 2.5.1 line for the token
  calls; the IT asserts the echo is present then.
- **Without Logbook:** the library's own log lines carry neither the body nor its
  `error_description`, on 2.5.1 or on 2.5.2.
