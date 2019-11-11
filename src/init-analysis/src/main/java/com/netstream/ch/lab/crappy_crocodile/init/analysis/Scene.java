package com.netstream.ch.lab.crappy_crocodile.init.analysis;

import com.google.cloud.videointelligence.v1p3beta1.Entity;
import com.google.cloud.videointelligence.v1p3beta1.ExplicitContentFrame;
import com.google.cloud.videointelligence.v1p3beta1.LabelAnnotation;
import com.google.cloud.videointelligence.v1p3beta1.LabelSegment;
import com.google.cloud.videointelligence.v1p3beta1.Likelihood;
import com.google.protobuf.Duration;

import javax.inject.Inject;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Created by Julian Hanhart (chdhaju0) on 08.11.19.
 */
public class Scene implements Comparable<Scene> {


    private static final String ENV_VAR_CONFIDENCE_THRESHOLD = "CONFIDENCE_THRESHOLD";
    private static final double CONFIDENCE_THRESHOLD =
            (System.getenv(ENV_VAR_CONFIDENCE_THRESHOLD) != null)
                    ? Float.parseFloat(System.getenv(ENV_VAR_CONFIDENCE_THRESHOLD))
                    : 0.5;


    private LocalTime start;
    private LocalTime end;
    private Set<SceneEntity> entities;
    private Likelihood likelihoodForExplicitContent;


    public Scene() {
        this.entities = new HashSet<>();
        this.likelihoodForExplicitContent = Likelihood.LIKELIHOOD_UNSPECIFIED;
    }

    public Scene(LocalTime start, LocalTime end) {
        this();
        this.start = start;
        this.end = end;
    }

    public static Scene create(Duration startOffset, Duration endOffset) {
        if (startOffset != null) {
            return new Scene(
                    LocalTime.ofSecondOfDay(startOffset.getSeconds()).withNano(startOffset.getNanos()),
                    LocalTime.ofSecondOfDay(endOffset.getSeconds()).withNano(endOffset.getNanos()));
        } else if (endOffset != null) {
            return new Scene(
                    LocalTime.of(0, 0, 0, 0),
                    LocalTime.ofSecondOfDay(endOffset.getSeconds()).withNano(endOffset.getNanos()));
        } else {
            return new Scene(
                    LocalTime.of(0, 0, 0, 0),
                    null);
        }
    }

    public static Scene create(
            Duration startOffset, Duration endOffset,
            float confidence, Entity entity, Collection<Entity> categoryEntities) {
        final Scene scene = create(startOffset, endOffset);
        scene.entities.add(SceneEntity.create(confidence, entity, categoryEntities));
        return scene;
    }

    public static SortedSet<Scene> from(LabelAnnotation annotation) {
        if ((annotation != null) && (annotation.getSegmentsCount() > 0)) {
            final SortedSet<Scene> scenes = new TreeSet<>();
            if (annotation.getSegmentsCount() == 1) {
                final LabelSegment segment = annotation.getSegments(0);
                final Scene scene = create(
                        segment.getSegment().getStartTimeOffset(),
                        segment.getSegment().getEndTimeOffset(),
                        segment.getConfidence(),
                        annotation.getEntity(),
                        annotation.getCategoryEntitiesList());
                scenes.add(scene);
            } else {
                scenes.addAll(
                        annotation.getSegmentsList().stream()
                                .filter(s -> s.getConfidence() >= CONFIDENCE_THRESHOLD)
                                .map(s -> create(
                                        s.getSegment().getStartTimeOffset(),
                                        s.getSegment().getEndTimeOffset(),
                                        s.getConfidence(),
                                        annotation.getEntity(),
                                        annotation.getCategoryEntitiesList()))
                                .collect(Collectors.toCollection(TreeSet::new)));
            }
            return scenes;
        } else {
            return new TreeSet<>();
        }
    }

    public static SortedSet<Scene> from(Collection<LabelAnnotation> annotations) {
        if ((annotations != null) && !annotations.isEmpty()) {
            final SortedSet<Scene> scenes = new TreeSet<>();
            for (LabelAnnotation annotation : annotations) {
                final SortedSet<Scene> newScenes = from(annotation);
                final Set<Scene> overlapping = scenes.stream()
                        .filter(s -> newScenes.stream().anyMatch(s::overlaps))
                        .collect(Collectors.toSet());
                if (overlapping.isEmpty()) {
                    scenes.addAll(newScenes);
                } else {
                    for (Scene scene : overlapping) {
                        for (Scene newScene : newScenes) {
                            if (scene.matchesTime(newScene)) {
                                scene.amendWith(newScene);
                            } else {
                                scenes.remove(scene);
                                if (newScene.start.isAfter(scene.start)
                                        && newScene.end.isBefore(scene.end)) {
                                    final SortedSet<Scene> split = scene.split(newScene.start, newScene.end);
                                    scenes.addAll(split.stream()
                                            .filter(s -> s.matchesTime(newScene))
                                            .map(s -> s.amendWith(newScene))
                                            .collect(Collectors.toSet()));
                                } else if (newScene.start.isBefore(scene.start)
                                        && newScene.end.isAfter(scene.start)
                                        && newScene.end.isBefore(scene.end)) {
                                    scenes.addAll(mergeOverlapping(
                                            scene, scene.split(newScene.end),
                                            newScene, newScene.split(scene.start)));
                                } else if (newScene.start.isBefore(scene.end)
                                        && newScene.start.isAfter(scene.start)
                                        && newScene.end.isAfter(scene.end)) {
                                    scenes.addAll(mergeOverlapping(
                                            scene, scene.split(newScene.start),
                                            newScene, newScene.split(scene.end)));
                                } else {
                                    scenes.add(scene);
                                }
                            }
                        }
                    }
                }
            }
            return scenes;
        } else {
            return new TreeSet<>();
        }
    }

    public static SortedSet<Scene> mergeOverlapping(
            Scene originalScene, SortedSet<Scene> originalSplit,
            Scene newScene, SortedSet<Scene> newSplit) {
        final SortedSet<Scene> scenes = new TreeSet<>();
        scenes.addAll(originalSplit.stream()
                .filter(s -> s.matchesTime(newScene))
                .map(s -> s.amendWith(
                        newSplit.stream()
                                .filter(s::matchesTime)
                                .findFirst()
                                .orElse(s)))
                .collect(Collectors.toSet()));
        scenes.addAll(newSplit.stream()
                .filter(n -> !n.endsWithin(originalScene))
                .collect(Collectors.toSet()));
        return scenes;
    }


    public Scene clone(LocalTime newStart, LocalTime newEnd) {
        final Scene scene = new Scene(LocalTime.from(newStart), LocalTime.from(newEnd));
        scene.entities.addAll(this.entities);
        scene.likelihoodForExplicitContent = this.likelihoodForExplicitContent;
        return scene;
    }

    public SortedSet<Scene> split(LocalTime... splitAt) {
        final SortedSet<Scene> scenes = new TreeSet<>();
        if (splitAt != null) {
            if (splitAt.length == 1) {
                scenes.add(clone(start, splitAt[0]));
                scenes.add(clone(splitAt[0], end));
            } else {
                scenes.add(clone(start, splitAt[0]));
                scenes.addAll(
                        split(Arrays.stream(splitAt)
                                .skip(1)
                                .toArray(LocalTime[]::new)));
            }
        } else {
            scenes.add(this);
        }
        return scenes;
    }

    public Scene amendWith(Scene scene) {
        if (matchesTime(scene)) {
            entities.addAll(scene.entities);
            if (scene.likelihoodForExplicitContent.getNumber() > likelihoodForExplicitContent.getNumber()) {
                likelihoodForExplicitContent = scene.likelihoodForExplicitContent;
            }
        }
        return (this);
    }

    @Override
    public int compareTo(Scene scene) {
        if (scene != null) {
            int startComparison = start.compareTo(scene.getStart());
            return  (startComparison != 0) ? startComparison : end.compareTo(scene.getEnd());
        } else {
            return -1;
        }
    }

    public boolean matchesTime(Scene scene) {
        return (scene != null) && start.equals(scene.start) && end.equals(scene.end);
    }

    public boolean overlaps(Scene scene) {
        return (startsWithin(scene) || endsWithin(scene));
    }

    public boolean startsWithin(Scene scene) {
        return (scene != null)
                && (start.equals(scene.start)
                        || (start.isAfter(scene.start) && start.isBefore(scene.end)));
    }

    public boolean endsWithin(Scene scene) {
        return (scene != null)
                && ((end.equals(scene.end))
                    || (end.isAfter(scene.start) && end.isBefore(scene.end)));
    }

    public Likelihood updateExplicitContentLikelihoodIfContains(ExplicitContentFrame frame) {
        if (frame != null) {
            final LocalTime frameOffset = LocalTime
                    .ofSecondOfDay(frame.getTimeOffset().getSeconds())
                    .withNano(frame.getTimeOffset().getNanos());
            if ((start.equals(frameOffset)
                    || (start.isBefore(frameOffset) && end.isAfter(frameOffset))
                    || end.equals(frameOffset))
                    && (frame.getPornographyLikelihood().getNumber() > likelihoodForExplicitContent.getNumber())) {
                likelihoodForExplicitContent = frame.getPornographyLikelihood();
            }
        }
        return likelihoodForExplicitContent;
    }

    public String toTextTrackLine(boolean includeCategories) {
        return String.format(
                "%s --> %s\n%s\n\n",
                start.toString(), end.toString(),
                entities.stream()
                        .map(e -> e.toDescription(includeCategories))
                        .collect(Collectors.joining(" - ")));
    }

    public String toEmojiTrackLine(EmojiConverter emojiConverter) {
        return String.format(
                "%s --> %s\n%s\n\n",
                start.toString(), end.toString(),
                entities.stream()
                        .map(e -> emojiConverter.convertToEmoji(e))
                        .collect(Collectors.joining(" - ")));
    }


    public LocalTime getStart() {
        return start;
    }

    public void setStart(LocalTime start) {
        this.start = start;
    }

    public LocalTime getEnd() {
        return end;
    }

    public void setEnd(LocalTime end) {
        this.end = end;
    }

    public Set<SceneEntity> getEntities() {
        return entities;
    }

    public void setEntities(Set<SceneEntity> entities) {
        this.entities = entities;
    }

    public Likelihood getLikelihoodForExplicitContent() {
        return likelihoodForExplicitContent;
    }

    public void setLikelihoodForExplicitContent(Likelihood likelihoodForExplicitContent) {
        this.likelihoodForExplicitContent = likelihoodForExplicitContent;
    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("Scene{");
        sb.append("start=").append(start);
        sb.append(", end=").append(end);
        sb.append(", entities=").append(entities);
        sb.append(", likelihoodForExplicitContent=").append(likelihoodForExplicitContent);
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Scene)) return false;

        Scene scene = (Scene) o;

        if (start != null ? !start.equals(scene.start) : scene.start != null) return false;
        if (end != null ? !end.equals(scene.end) : scene.end != null) return false;
        if (entities != null ? !entities.equals(scene.entities) : scene.entities != null) return false;
        return likelihoodForExplicitContent == scene.likelihoodForExplicitContent;
    }

    @Override
    public int hashCode() {
        int result = start != null ? start.hashCode() : 0;
        result = 31 * result + (end != null ? end.hashCode() : 0);
        result = 31 * result + (entities != null ? entities.hashCode() : 0);
        result = 31 * result + (likelihoodForExplicitContent != null ? likelihoodForExplicitContent.hashCode() : 0);
        return result;
    }

}
