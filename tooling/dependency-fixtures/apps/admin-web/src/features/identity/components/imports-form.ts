// Deliberate violation of admin-presentation-is-pure: presentation importing the form library.
import { useForm } from "@tanstack/react-form";

export const leaked = useForm;
