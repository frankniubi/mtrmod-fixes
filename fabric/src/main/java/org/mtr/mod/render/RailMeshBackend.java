package org.mtr.mod.render;

import org.mtr.mapping.mapper.OptimizedModel;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * GPU side of {@link DefaultRailMeshCache}. The implementation uses MTR's internal render API, which some Minecraft versions (1.16.5) do not ship,
 * so it is loaded reflectively and left out of those builds; without it the cache stays off and rails use the legacy renderer.
 */
public interface RailMeshBackend {

	UploadedMesh upload(Consumer<VertexSink> geometry);

	void captureGlState();

	void restoreGlState();

	@Nullable
	static RailMeshBackend get() {
		return Holder.INSTANCE;
	}

	@FunctionalInterface
	interface VertexSink {
		void accept(float x, float y, float z, float u, float v, int light);
	}

	interface UploadedMesh extends AutoCloseable {

		OptimizedModel getModel();

		@Override
		void close();
	}

	final class Holder {

		@Nullable
		private static final RailMeshBackend INSTANCE = load();

		private Holder() {
		}

		@Nullable
		private static RailMeshBackend load() {
			try {
				return (RailMeshBackend) Class.forName("org.mtr.mod.render.GlRailMeshBackend").getDeclaredConstructor().newInstance();
			} catch (ReflectiveOperationException | LinkageError ignored) {
				return null;
			}
		}
	}
}
