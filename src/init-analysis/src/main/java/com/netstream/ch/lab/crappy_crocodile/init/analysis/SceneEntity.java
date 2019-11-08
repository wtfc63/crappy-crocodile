package com.netstream.ch.lab.crappy_crocodile.init.analysis;

import com.google.cloud.videointelligence.v1p3beta1.Entity;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Created by Julian Hanhart (chdhaju0) on 08.11.19.
 */
class SceneEntity {

    private float confidence;
    private Entity entity;
    private Set<Entity> categoryEntities;

    public SceneEntity() {
        this.categoryEntities = Collections.emptySet();
    }

    private SceneEntity(float confidence, Entity entity) {
        this();
        this.confidence = confidence;
        this.entity = entity;
    }

    public static SceneEntity create(float confidence, Entity entity) {
        return new SceneEntity(confidence, entity);
    }

    public static SceneEntity create(float confidence, Entity entity, Collection<Entity> categoryEntities) {
        final SceneEntity se = new SceneEntity(confidence, entity);
        if ((categoryEntities != null) && !categoryEntities.isEmpty()) {
            se.setCategoryEntities(new HashSet<>(categoryEntities));
        }
        return se;
    }

    public String toDescription(boolean includeCategory) {
        StringBuilder description = new StringBuilder(entity.getDescription());
        if (includeCategory && !categoryEntities.isEmpty()) {
            description
                    .append(" (")
                    .append(categoryEntities.stream()
                            .map(Entity::getDescription)
                            .collect(Collectors.joining("/")))
                    .append(')');
        }
        return description.toString();
    }

    public Entity getEntity() {
        return entity;
    }

    public void setEntity(Entity entity) {
        this.entity = entity;
    }

    public Set<Entity> getCategoryEntities() {
        return categoryEntities;
    }

    public void setCategoryEntities(Set<Entity> categoryEntities) {
        this.categoryEntities = categoryEntities;
    }

    public float getConfidence() {
        return confidence;
    }

    public void setConfidence(float confidence) {
        this.confidence = confidence;
    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("SceneEntity{");
        sb.append("confidence=").append(confidence);
        sb.append(", entity=").append(entity);
        sb.append(", categoryEntities=").append(categoryEntities);
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneEntity)) return false;

        SceneEntity entity1 = (SceneEntity) o;

        if (Float.compare(entity1.confidence, confidence) != 0) return false;
        if (entity != null ? !entity.equals(entity1.entity) : entity1.entity != null) return false;
        return categoryEntities != null ? categoryEntities.equals(entity1.categoryEntities) : entity1.categoryEntities == null;
    }

    @Override
    public int hashCode() {
        int result = (confidence != +0.0f ? Float.floatToIntBits(confidence) : 0);
        result = 31 * result + (entity != null ? entity.hashCode() : 0);
        result = 31 * result + (categoryEntities != null ? categoryEntities.hashCode() : 0);
        return result;
    }

}
