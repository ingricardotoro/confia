import { useNavigate } from "@tanstack/react-router";
import { ACCESS_PATHS } from "../features/identity/routes.tsx";
import { NotFound } from "./components/NotFound.tsx";

/** The not found route of the router: the presentation, and the navigation it triggers. */
export function NotFoundContainer() {
  const navigate = useNavigate();

  return <NotFound href={ACCESS_PATHS.signIn} onNavigate={() => void navigate({ to: ACCESS_PATHS.signIn })} />;
}
