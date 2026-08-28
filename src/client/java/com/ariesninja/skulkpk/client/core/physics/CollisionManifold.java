package com.ariesninja.skulkpk.client.core.physics;

import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Objects;

/** Complete collision result for one requested movement transition. */
public record CollisionManifold(
        List<CollisionContact> contacts,
        Vec3d requestedMovement,
        Vec3d resolvedMovement
) {
    public CollisionManifold {
        contacts = List.copyOf(contacts);
        requestedMovement = Objects.requireNonNull(requestedMovement);
        resolvedMovement = Objects.requireNonNull(resolvedMovement);
    }

    public boolean hasHeadContact() {
        return contacts.stream().anyMatch(contact -> contact.face().headContact());
    }

    public boolean hasSideContact() {
        return contacts.stream().anyMatch(contact -> contact.face().sideContact());
    }

    public boolean hasSupportContact() {
        return contacts.stream().anyMatch(CollisionContact::support);
    }
}
