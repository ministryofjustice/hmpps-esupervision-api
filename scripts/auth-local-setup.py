#!/usr/bin/env python3
"""Add the eSupervision roles to the local HMPPS Auth clients and services."""

import argparse
import getpass
import http.cookiejar
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser

BASE_URL = "http://localhost:8090/auth"
CLIENT_ID = "hmpps-typescript-template-system"
CLIENT_PATH = f"/new-ui/clients/{CLIENT_ID}"
AUTHORITY = "ESUPERVISION__ESUPERVISION_UI"
SERVICE_CODE = "hmpps-typescript-template"
SERVICE_FORM_PATH = "/ui/services/form"
SERVICE_ROLE = "ESUPERVISION__PRACTITIONER__RW"
SERVICE_FIELDS = ("code", "name", "description", "authorisedRolesWithNewlines", "url", "email", "enabled")


class SetupError(Exception):
    pass


class FormParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.forms = []
        self.current = None
        self.textarea = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "form":
            if self.current is not None:
                raise SetupError("Unexpected nested form.")
            self.current = {
                "action": attrs.get("action", ""),
                "method": attrs.get("method", "get").lower(),
                "fields": [],
            }
        elif self.current is not None:
            name = attrs.get("name")
            if not name or "disabled" in attrs:
                return
            if tag in ("select", "fieldset"):
                raise SetupError(f"Unsupported form control: {tag}. Check the Auth UI.")
            if tag == "input":
                kind = attrs.get("type", "text").lower()
                if kind in ("submit", "button", "reset"):
                    return
                if kind in ("checkbox", "radio") and "checked" not in attrs:
                    return
                if kind not in (
                    "hidden", "text", "password", "number", "checkbox", "radio",
                ):
                    raise SetupError(f"Unsupported input type: {kind}.")
                default = "on" if kind in ("checkbox", "radio") else ""
                self.current["fields"].append((name, attrs.get("value", default)))
            elif tag == "textarea":
                self.textarea = (name, [])

    def handle_data(self, data):
        if self.textarea is not None:
            self.textarea[1].append(data)

    def handle_endtag(self, tag):
        if tag == "textarea" and self.textarea is not None:
            name, parts = self.textarea
            self.current["fields"].append((name, "".join(parts)))
            self.textarea = None
        elif tag == "form" and self.current is not None:
            self.forms.append(self.current)
            self.current = None


def read_form(html, action):
    parser = FormParser()
    parser.feed(html)
    if parser.current is not None:
        raise SetupError("Incomplete Auth form.")
    forms = [form for form in parser.forms if form["action"] == action]
    if len(forms) != 1 or forms[0]["method"] != "post":
        raise SetupError(f"Expected POST form not found at {action}. Check login and Auth version.")
    fields = forms[0]["fields"]
    field_values = {}
    for name, value in fields:
        field_values.setdefault(name, []).append(value)
    if len(field_values.get("_csrf", [])) != 1 or not field_values["_csrf"][0]:
        raise SetupError("Missing or ambiguous CSRF token.")
    return field_values


def one_value(fields, name):
    values = fields.get(name, [])
    if len(values) != 1:
        raise SetupError(f"Missing or ambiguous form field: {name}.")
    return values[0]


def authorities(value):
    return set(filter(None, re.split(r"[\s,]+", value)))


def role_names(value):
    return {role.upper().removeprefix("ROLE_") for role in authorities(value)}


class LocalRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl)[:2] != urllib.parse.urlsplit(BASE_URL)[:2]:
            raise SetupError("Auth attempted to redirect outside the local service.")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class LocalAuth:
    def __init__(self):
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
            LocalRedirectHandler(),
        )

    def request(self, path, fields=None):
        data = None
        if fields is not None:
            data = urllib.parse.urlencode(fields, doseq=True).encode()
        try:
            with self.opener.open(BASE_URL + path, data=data, timeout=15) as response:
                return response.geturl(), response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            raise SetupError(f"Auth returned HTTP {error.code} for {path}.") from error
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise SetupError(f"Cannot reach local Auth at {BASE_URL}: {error.reason if isinstance(error, urllib.error.URLError) else error}") from error

    def client_form(self):
        path = CLIENT_PATH + "/details"
        url, html = self.request(path)
        if url != BASE_URL + path:
            raise SetupError("Could not access client details. Check admin login.")
        fields = read_form(html, "/auth" + path)
        for name in (
            "clientId", "grantType", "accessTokenValiditySeconds", "scopes",
            "jiraNumber", "validDays", "ips", "authorities", "databaseUserName",
        ):
            one_value(fields, name)
        if fields["clientId"] != [CLIENT_ID] or fields["grantType"] != ["client_credentials"]:
            raise SetupError("Unexpected client identity or grant type; no update submitted.")
        return fields

    def login(self, username, password):
        url, html = self.request("/sign-in")
        if url != BASE_URL + "/sign-in":
            raise SetupError("Unexpected login page.")
        fields = read_form(html, "/auth/sign-in")
        fields.update(username=[username], password=[password])
        url, _ = self.request("/sign-in", fields)
        if urllib.parse.urlsplit(url).path == "/auth/sign-in":
            raise SetupError("Admin login failed. Check username and password.")

    def configure(self, username, password):
        self.login(username, password)
        return self.configure_client(), self.configure_service()

    def configure_client(self):
        before = self.client_form()
        existing = one_value(before, "authorities")
        if AUTHORITY in authorities(existing):
            return False
        updated = dict(before)
        updated["authorities"] = [existing.rstrip() + ("\n" if existing.strip() else "") + AUTHORITY]
        url, _ = self.request(CLIENT_PATH + "/details", updated)
        if url != BASE_URL + CLIENT_PATH:
            raise SetupError("Auth did not confirm the save. Check the client form for validation errors.")

        after = self.client_form()
        if authorities(one_value(after, "authorities")) != authorities(existing) | {AUTHORITY}:
            raise SetupError("Saved authorities do not match the requested update.")
        for name in (before.keys() | after.keys()) - {"_csrf", "authorities"}:
            if before.get(name) != after.get(name):
                raise SetupError(f"Client setting {name} changed unexpectedly; inspect the Auth UI.")
        return True

    def service_exists(self):
        url, html = self.request("/ui/services")
        if url != BASE_URL + "/ui/services":
            raise SetupError("Could not access the services dashboard. Check admin login.")
        return f'href="/auth{SERVICE_FORM_PATH}?code={SERVICE_CODE}"' in html

    def service_form(self, new):
        query = {"code": SERVICE_CODE, **({"newService": "true"} if new else {})}
        path = SERVICE_FORM_PATH + "?" + urllib.parse.urlencode(query)
        url, html = self.request(path)
        if url != BASE_URL + path:
            raise SetupError("Could not access service details. Check admin login.")
        fields = read_form(html, "/auth" + SERVICE_FORM_PATH)
        for name in SERVICE_FIELDS:
            one_value(fields, name)
        if fields["code"] != [SERVICE_CODE] or fields.get("newService") != (["true"] if new else None):
            raise SetupError("Unexpected service form; no update submitted.")
        return fields

    def configure_service(self):
        exists = self.service_exists()
        before = self.service_form(new=not exists)
        existing = one_value(before, "authorisedRolesWithNewlines")
        has_role = SERVICE_ROLE in role_names(existing)
        if exists and has_role and one_value(before, "enabled") == "true":
            return False
        updated = {name: value for name, value in before.items() if name != "fromClient"}
        if not has_role:
            updated["authorisedRolesWithNewlines"] = [existing.rstrip() + ("\n" if existing.strip() else "") + SERVICE_ROLE]
        updated["enabled"] = ["true"]
        if not exists and not one_value(before, "name").strip():
            updated["name"] = [SERVICE_CODE]
        url, _ = self.request(SERVICE_FORM_PATH, updated)
        if url != BASE_URL + "/ui/services":
            raise SetupError("Auth did not confirm the service save. Check the service form for validation errors.")

        if not self.service_exists():
            raise SetupError("Saved service is missing from the services dashboard.")
        after = self.service_form(new=False)
        if role_names(one_value(after, "authorisedRolesWithNewlines")) != role_names(existing) | {SERVICE_ROLE}:
            raise SetupError("Saved service roles do not match the requested update.")
        for name in set(SERVICE_FIELDS) - {"authorisedRolesWithNewlines"}:
            if updated.get(name) != after.get(name):
                raise SetupError(f"Service setting {name} changed unexpectedly; inspect the Auth UI.")
        return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--username", default="AUTH_ADM", help="Local Auth admin (default: AUTH_ADM)")
    args = parser.parse_args()
    password = os.environ.get("HMPPS_AUTH_PASSWORD") or "password123456"
    # if password is None:
    #     if not sys.stdin.isatty():
    #         parser.error("Set HMPPS_AUTH_PASSWORD for non-interactive use.")
    #     password = getpass.getpass("Local Auth admin password: ")
    # if not password:
    #     parser.error("The admin password must not be empty.")
    try:
        client_changed, service_changed = LocalAuth().configure(args.username, password)
    except SetupError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    status = {True: "added and verified", False: "already configured"}
    print(f"{CLIENT_ID} client authority: {AUTHORITY} {status[client_changed]}.")
    print(f"{SERVICE_CODE} service (enabled, role {SERVICE_ROLE}): {status[service_changed]}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
