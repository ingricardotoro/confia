package com.confia.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The detectors of what change 8 deliberately leaves out and the changes that own it deliver later
 * (rbac-permission-matrix-and-audit-integration, specs/authorization AZ74 to AZ77, specs/identity I215
 * and I300, specs/build-integrity BI136). They are shared by the inventories that assert each absence,
 * so one definition of "a route that manages roles" serves them all, and each one is proven on a
 * fixture that has what it looks for. Test support, never production code.
 */
public final class AuthorizationAbsences {

    /** The one route of the identity module this change offers: the report of what each role can do. */
    public static final String ROLES_REPORT = "GET /api/v1/identity/roles";

    /** The two routes that take a restricted second-factor token, and the only ones of the MFA path. */
    public static final List<String> RESTRICTED_MFA_ROUTES = List.of(
            "POST /api/v1/auth/mfa/enrollments", "POST /api/v1/auth/mfa/verifications");

    /** The name of a use case that deactivates an account (change 8b). */
    public static final Pattern DEACTIVATION = Pattern.compile("(?i)"
            + "((Deactivat|Disable|Suspend|Block|Lock)\\w*(Account|Staff)\\w*"
            + "|(Account|Staff)\\w*(Deactivat|Disabl|Suspen|Block|Lock)\\w*)");

    /** The name of a use case that assigns or changes roles, or creates the first Super Admin (change 8b). */
    public static final Pattern ROLE_MANAGEMENT = Pattern.compile(
            "^(Assign|Grant|Revoke|Change|Update|Set|Promote|Demote|Create|Bootstrap|Seed)\\w*"
                    + "(Role|Permission|SuperAdmin)\\w*$");

    private static final Pattern ACCOUNT_OR_ROLE_PATH = Pattern.compile(
            "(?i).*/(roles|accounts|users|staff|permissions|assignments)(/.*)?");
    private static final Pattern AUDIT_PATH = Pattern.compile("(?i).*(audit|bitacora).*");
    private static final Pattern MFA_PATH = Pattern.compile("(?i).*/(mfa|totp|second-factor)(/.*|-.*)?");
    private static final Pattern PASSWORD_PATH = Pattern.compile(
            "(?i).*(password|passwd|recover|forgot|reset).*");

    private static final Set<String> WRITING_CALLS = Set.of("insertInto", "update", "deleteFrom",
            "mergeInto", "batchInsert", "batchUpdate", "batchDelete", "batchMerge", "truncate");
    private static final String ASSIGNMENT_TABLE_OWNER =
            "confia.generated.jooq.tables.IdentityStaffAccountRole";

    private AuthorizationAbsences() {
    }

    /** The simple names of the classes of {@code classes} that live in the package {@code packageName}. */
    public static List<String> simpleNamesIn(JavaClasses classes, String packageName) {
        return classes.stream().filter(type -> type.getPackageName().equals(packageName))
                .map(JavaClass::getSimpleName).sorted().toList();
    }

    /** The names among {@code names} that look like a use case that assigns or changes roles. */
    public static List<String> roleManagementNames(List<String> names) {
        return names.stream().filter(name -> ROLE_MANAGEMENT.matcher(name).matches()).toList();
    }

    /** The names among {@code names} that look like a use case that deactivates an account. */
    public static List<String> deactivationNames(List<String> names) {
        return names.stream().filter(name -> DEACTIVATION.matcher(name).matches()).toList();
    }

    /**
     * The routes among {@code routes} (as {@code "METHOD path"}) that manage roles or accounts, read the
     * audit trail, or enroll a second factor with anything but the two restricted routes, each with the
     * reason. The report of the roles is the one route allowed.
     */
    public static List<String> forbiddenRoutes(List<String> routes) {
        Set<String> found = new TreeSet<>();
        for (String route : routes) {
            String path = route.substring(route.indexOf(' ') + 1);
            if (route.equals(ROLES_REPORT)) {
                continue;
            }
            if (ACCOUNT_OR_ROLE_PATH.matcher(path).matches()) {
                found.add("manages roles or accounts: " + route);
            }
            if (AUDIT_PATH.matcher(path).matches()) {
                found.add("reads the audit trail: " + route);
            }
            if (MFA_PATH.matcher(path).matches() && !RESTRICTED_MFA_ROUTES.contains(route)) {
                found.add("enrolls or removes a second factor with a full session: " + route);
            }
            if (PASSWORD_PATH.matcher(path).matches()) {
                found.add("recovers or resets a password: " + route);
            }
        }
        return List.copyOf(found);
    }

    /** The routes among {@code routes} that read the audit trail. */
    public static List<String> auditLogRoutes(List<String> routes) {
        return routes.stream().filter(route -> AUDIT_PATH.matcher(
                route.substring(route.indexOf(' ') + 1)).matches()).toList();
    }

    /** The classes that reach the table of the role assignments through a jOOQ call that writes. */
    public static List<String> roleAssignmentWriters(JavaClasses classes) {
        return classes.stream().filter(AuthorizationAbsences::touchesTheAssignmentTable)
                .filter(AuthorizationAbsences::callsAWritingOperation)
                .map(JavaClass::getFullName).sorted().toList();
    }

    /** The classes that reach the table of the role assignments at all. */
    public static List<String> roleAssignmentUsers(JavaClasses classes) {
        return classes.stream().filter(AuthorizationAbsences::touchesTheAssignmentTable)
                .map(JavaClass::getFullName).sorted().toList();
    }

    private static boolean touchesTheAssignmentTable(JavaClass type) {
        for (JavaFieldAccess access : type.getFieldAccessesFromSelf()) {
            if (access.getTarget().getOwner().getFullName().equals(ASSIGNMENT_TABLE_OWNER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean callsAWritingOperation(JavaClass type) {
        for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
            if (WRITING_CALLS.contains(call.getTarget().getName())
                    && call.getTarget().getOwner().getFullName().startsWith("org.jooq.")) {
                return true;
            }
        }
        return false;
    }

    /** Every {@code .sql} file under the real {@code db/migration} classpath directory, by file name. */
    public static java.util.Map<String, String> migrationSqlByFile() {
        java.net.URL directory = AuthorizationAbsences.class.getClassLoader().getResource("db/migration");
        if (directory == null) {
            throw new IllegalStateException("db/migration classpath directory not found");
        }
        try (var files = java.nio.file.Files.list(java.nio.file.Path.of(directory.toURI()))) {
            java.util.Map<String, String> byFile = new java.util.TreeMap<>();
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                byFile.put(file.getFileName().toString(), java.nio.file.Files.readString(file,
                        java.nio.charset.StandardCharsets.UTF_8));
            }
            return byFile;
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("malformed db/migration classpath URL", e);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The migrations among {@code migrations} (file name to SQL text) that insert accounts or assignments. */
    public static List<String> migrationsThatInsertAccountsOrAssignments(
            java.util.Map<String, String> migrations) {
        Pattern insert = Pattern.compile(
                "insert\\s+into\\s+(public\\.)?identity_staff_account(_role)?\\b");
        return migrations.entrySet().stream()
                .filter(entry -> insert.matcher(entry.getValue().toLowerCase(Locale.ROOT)).find())
                .map(java.util.Map.Entry::getKey).sorted().toList();
    }
}
