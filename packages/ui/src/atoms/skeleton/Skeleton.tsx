import type { ComponentProps } from "react";
import { cn } from "../../lib/cn";

// A placeholder block for content that is loading (docs/ui-ux/02 section 6). It is hidden from
// assistive technology because it carries nothing to announce; the container that owns the loading
// state announces it. The caller gives it the shape of the real content with its class. Under
// prefers-reduced-motion the pulse shrinks to almost nothing through base.css.
export type SkeletonProps = Omit<ComponentProps<"div">, "children" | "aria-hidden">;

export function Skeleton({ className, ...rest }: SkeletonProps) {
  return (
    <div
      {...rest}
      aria-hidden="true"
      className={cn("animate-(--skeleton-animation) rounded-md bg-(--skeleton-bg)", className)}
    />
  );
}
