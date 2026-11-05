// Deliberate violation of admin-presentation-is-pure: presentation importing the router.
import { useNavigate } from "@tanstack/react-router";

export const leaked = useNavigate;
