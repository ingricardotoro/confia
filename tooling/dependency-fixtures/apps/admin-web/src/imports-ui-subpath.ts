// Deliberate violation of apps-use-ui-public-entry (AW04): a package subpath that @confia/ui does not
// export. It never resolves, so the rule matches the name as written.
import "@confia/ui/src/atoms/button/Button";
