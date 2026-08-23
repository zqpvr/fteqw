/*
	r_swrt.c -- software ray tracing acceleration structure.

	FTE can already trace shadow rays through VK_KHR_ray_query, but that needs a
	GPU with raytracing hardware behind it, which most phones do not have. This
	builds the same thing in software: a bounding volume hierarchy over the
	world's triangles, laid out so it can be handed to a shader as a plain
	storage buffer and walked there.

	Only the structure lives here. Uploading it and tracing against it belong to
	the renderer backends, so this stays free of any GL or Vulkan specifics and
	can be built and inspected on its own -- see the swrt_build command.
*/

#include "quakedef.h"

#ifdef SWRT

#define SWRT_LEAF_TRIS	4	//stop splitting once a node holds this few
#define SWRT_MAX_DEPTH	48

typedef struct
{
	vec3_t	v[3];
} swrttri_t;

typedef struct
{
	vec3_t	mins, maxs;
	int		child;	//interior: index of the first of two children. leaf: first triangle.
	int		count;	//0 for an interior node, otherwise the triangle count.
} swrtnode_t;

static swrttri_t	*swrt_tris;
static int			 swrt_numtris;
static swrtnode_t	*swrt_nodes;
static int			 swrt_numnodes;
static int			 swrt_maxnodes;
static int			 swrt_maxdepth;

void SWRT_Shutdown(void)
{
	Z_Free(swrt_tris);
	Z_Free(swrt_nodes);
	swrt_tris = NULL;
	swrt_nodes = NULL;
	swrt_numtris = swrt_numnodes = swrt_maxnodes = swrt_maxdepth = 0;
}

//A sky brush should not stop a ray: the light beyond it is the point. Water and
//other warped surfaces are seen through, so they do not occlude either.
static qboolean SWRT_SurfaceOccludes(msurface_t *surf)
{
	if (!surf->mesh || surf->mesh->numindexes < 3)
		return false;
	if (surf->flags & (SURF_DRAWSKY|SURF_DRAWTURB))
		return false;
	return true;
}

static void SWRT_BoundTris(int first, int count, vec3_t mins, vec3_t maxs)
{
	int i, j;

	ClearBounds(mins, maxs);
	for (i = 0; i < count; i++)
		for (j = 0; j < 3; j++)
			AddPointToBounds(swrt_tris[first+i].v[j], mins, maxs);
}

//Split on the middle of the widest axis. Cheaper to build than a full sweep
//over surface-area heuristics, and the maps this runs on are small enough that
//the difference in traversal cost does not pay for the extra build time.
static void SWRT_BuildNode(int nodenum, int first, int count, int depth)
{
	swrtnode_t	*node = &swrt_nodes[nodenum];
	int			 axis, i, mid;
	vec3_t		 size;
	float		 split;
	swrttri_t	 swap;

	SWRT_BoundTris(first, count, node->mins, node->maxs);

	if (depth > swrt_maxdepth)
		swrt_maxdepth = depth;

	if (count <= SWRT_LEAF_TRIS || depth >= SWRT_MAX_DEPTH)
	{
		node->child = first;
		node->count = count;
		return;
	}

	VectorSubtract(node->maxs, node->mins, size);
	axis = 0;
	if (size[1] > size[axis]) axis = 1;
	if (size[2] > size[axis]) axis = 2;
	split = (node->mins[axis] + node->maxs[axis]) * 0.5;

	//partition in place: everything below the split moves to the front
	mid = first;
	for (i = first; i < first+count; i++)
	{
		float centre = (swrt_tris[i].v[0][axis] + swrt_tris[i].v[1][axis] + swrt_tris[i].v[2][axis]) / 3.0;
		if (centre < split)
		{
			swap = swrt_tris[i];
			swrt_tris[i] = swrt_tris[mid];
			swrt_tris[mid] = swap;
			mid++;
		}
	}

	//a split that separates nothing would recurse forever; halve the range instead
	if (mid == first || mid == first+count)
		mid = first + count/2;

	if (swrt_numnodes + 2 > swrt_maxnodes)
	{	//every triangle can end up its own leaf, so this should not be reachable
		node->child = first;
		node->count = count;
		return;
	}

	node->child = swrt_numnodes;
	node->count = 0;
	swrt_numnodes += 2;

	SWRT_BuildNode(node->child+0, first, mid-first, depth+1);
	SWRT_BuildNode(node->child+1, mid, first+count-mid, depth+1);
}

qboolean SWRT_Build(model_t *mod)
{
	int			 s, i, maxtris = 0;
	msurface_t	*surf;

	SWRT_Shutdown();

	if (!mod || mod->loadstate != MLS_LOADED || !mod->numsurfaces)
		return false;

	for (s = 0; s < mod->numsurfaces; s++)
		if (SWRT_SurfaceOccludes(&mod->surfaces[s]))
			maxtris += mod->surfaces[s].mesh->numindexes / 3;
	if (!maxtris)
		return false;

	swrt_tris = Z_Malloc(sizeof(*swrt_tris) * maxtris);

	for (s = 0; s < mod->numsurfaces; s++)
	{
		mesh_t *m;
		surf = &mod->surfaces[s];
		if (!SWRT_SurfaceOccludes(surf))
			continue;
		m = surf->mesh;

		for (i = 0; i+2 < m->numindexes; i += 3)
		{
			index_t a = m->indexes[i+0], b = m->indexes[i+1], c = m->indexes[i+2];
			if (a >= m->numvertexes || b >= m->numvertexes || c >= m->numvertexes)
				continue;	//don't trust the data more than we have to
			VectorCopy(m->xyz_array[a], swrt_tris[swrt_numtris].v[0]);
			VectorCopy(m->xyz_array[b], swrt_tris[swrt_numtris].v[1]);
			VectorCopy(m->xyz_array[c], swrt_tris[swrt_numtris].v[2]);
			swrt_numtris++;
		}
	}

	if (!swrt_numtris)
	{
		SWRT_Shutdown();
		return false;
	}

	//a binary tree over N leaves of at least SWRT_LEAF_TRIS triangles needs
	//fewer than 2N/SWRT_LEAF_TRIS nodes; round up generously and be done
	swrt_maxnodes = swrt_numtris * 2 + 8;
	swrt_nodes = Z_Malloc(sizeof(*swrt_nodes) * swrt_maxnodes);
	swrt_numnodes = 1;
	SWRT_BuildNode(0, 0, swrt_numtris, 0);

	return true;
}

int SWRT_NumTriangles(void) { return swrt_numtris; }
int SWRT_NumNodes(void)     { return swrt_numnodes; }

static void SWRT_Build_f(void)
{
	unsigned int start = Sys_Milliseconds();

	if (!cl.worldmodel)
	{
		Con_Printf("swrt: no world loaded\n");
		return;
	}

	if (!SWRT_Build(cl.worldmodel))
	{
		Con_Printf("swrt: nothing to build from\n");
		return;
	}

	Con_Printf("swrt: %i triangles, %i nodes, depth %i, %ums, %ukb\n",
			swrt_numtris, swrt_numnodes, swrt_maxdepth,
			Sys_Milliseconds() - start,
			(unsigned int)((swrt_numtris*sizeof(*swrt_tris) + swrt_numnodes*sizeof(*swrt_nodes))>>10));
}

void SWRT_Init(void)
{
	Cmd_AddCommand("swrt_build", SWRT_Build_f);
}

#endif
