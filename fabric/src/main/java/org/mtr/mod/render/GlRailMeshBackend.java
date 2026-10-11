package org.mtr.mod.render;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.Vector3f;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.render.batch.MaterialProperties;
import org.mtr.mapping.render.model.Mesh;
import org.mtr.mapping.render.model.RawMesh;
import org.mtr.mapping.render.object.IndexBuffer;
import org.mtr.mapping.render.object.VertexArray;
import org.mtr.mapping.render.object.VertexBuffer;
import org.mtr.mapping.render.tool.GlStateTracker;
import org.mtr.mapping.render.vertex.Vertex;
import org.mtr.mapping.render.vertex.VertexAttributeMapping;
import org.mtr.mapping.render.vertex.VertexAttributeSource;
import org.mtr.mapping.render.vertex.VertexAttributeType;

import java.util.Collections;
import java.util.function.Consumer;

/**
 * The only class that touches MTR's internal render API; excluded from builds whose mappings lack it (see fabric/build.gradle).
 */
public final class GlRailMeshBackend implements RailMeshBackend {

	static final VertexAttributeMapping RAIL_MAPPING = new VertexAttributeMapping.Builder()
			.set(VertexAttributeType.POSITION, VertexAttributeSource.VERTEX_BUFFER)
			.set(VertexAttributeType.COLOR, VertexAttributeSource.GLOBAL)
			.set(VertexAttributeType.UV_TEXTURE, VertexAttributeSource.VERTEX_BUFFER)
			.set(VertexAttributeType.UV_OVERLAY, VertexAttributeSource.GLOBAL)
			.set(VertexAttributeType.UV_LIGHTMAP, VertexAttributeSource.VERTEX_BUFFER)
			.set(VertexAttributeType.NORMAL, VertexAttributeSource.VERTEX_BUFFER)
			.set(VertexAttributeType.MATRIX_MODEL, VertexAttributeSource.GLOBAL)
			.build();
	private static final MaterialProperties RAIL_MATERIAL = new MaterialProperties(OptimizedModel.ShaderType.CUTOUT, new Identifier("textures/block/rail.png"), null);

	@Override
	public UploadedMesh upload(Consumer<VertexSink> geometry) {
		final RawMesh rawMesh = new RawMesh(RAIL_MATERIAL);
		geometry.accept((x, y, z, u, v, light) -> rawMesh.addVertex(vertex(x, y, z, u, v, light)));
		rawMesh.distinct();
		rawMesh.triangulate();
		final Mesh mesh = new Mesh(new VertexBuffer(), new IndexBuffer(rawMesh.faces.size(), 0x1405), RAIL_MATERIAL);
		VertexArray vertexArray = null;
		try {
			rawMesh.upload(mesh, RAIL_MAPPING);
			vertexArray = new VertexArray(mesh, RAIL_MAPPING);
			return new GlUploadedMesh(mesh, new OptimizedModel(Collections.singletonList(vertexArray)));
		} catch (RuntimeException | Error exception) {
			if (vertexArray != null) {
				vertexArray.close();
			}
			mesh.close();
			throw exception;
		}
	}

	@Override
	public void captureGlState() {
		GlStateTracker.capture();
	}

	@Override
	public void restoreGlState() {
		GlStateTracker.restore();
	}

	static Vertex vertex(float x, float y, float z, float u, float v, int light) {
		final Vertex vertex = new Vertex();
		vertex.position = new Vector3f(x, y, z);
		// Vertex equality omits light; subnormal components keep differently lit vertices distinct without changing the rendered normal.
		vertex.normal = new Vector3f((light & 0xFFFF) * Float.MIN_VALUE, 1, ((light >>> 16) & 0xFFFF) * Float.MIN_VALUE);
		vertex.u = u;
		vertex.v = v;
		vertex.light = light;
		return vertex;
	}

	private static final class GlUploadedMesh implements UploadedMesh {

		private final Mesh mesh;
		private final OptimizedModel model;

		private GlUploadedMesh(Mesh mesh, OptimizedModel model) {
			this.mesh = mesh;
			this.model = model;
		}

		@Override
		public OptimizedModel getModel() {
			return model;
		}

		@Override
		public void close() {
			model.close();
			mesh.close();
		}
	}
}
