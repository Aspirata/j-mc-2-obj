/*******************************************************************************
 * Copyright (c) 2012
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 ******************************************************************************/
package org.jmc;

import org.jmc.BlockInfo.Occlusion;
import org.jmc.NBT.*;
import org.jmc.geom.BlockPos;
import org.jmc.models.None;
import org.jmc.registry.NamespaceID;
import org.jmc.util.IDConvert;
import org.jmc.util.Log;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedList;
import java.util.List;
/**
 * Class describing a chunk. A chunk is a 16x16 group of blocks of 
 * varying heights (in Anvil) or 128 (in Region).
 * @author danijel
 *
 */
public class Chunk {

	/**
	 * Root of the loaded chunk structure.
	 */
	private TAG_Compound root;
	private TAG_Compound entities_root;
	
	public final int chunkVer;
	
	private int[] yMinMax = null;

	/**
	 * Is the chunk type new Anvil or not.
	 * Used to determine how to properly analyze the data.
	 */
	private final boolean is_anvil;

	/**
	 * 64x64 color image of topmost blocks in chunk.  
	 */
	private BufferedImage block_image;
	/**
	 * 64x64 grey-scale image of height of topmost blocks.
	 */
	private BufferedImage height_image;

	/**
	 * Main constructor of chunks. 
	 * @param is input stream located at the place in the file where the chunk begins 
	 * @param entityIs 
	 * @param is_anvil is the file new Anvil or old Region format
	 * @throws Exception throws errors while parsing the chunk
	 */
	public Chunk(InputStream is, InputStream entityIs, boolean is_anvil) throws Exception
	{
		this.is_anvil=is_anvil;
		if (is == null) {
			throw new IllegalArgumentException("Chunk InputStream null!");
		}
		
		root=(TAG_Compound) NBT_Tag.make(is);
		is.close();
		if (entityIs != null) {
			entities_root = (TAG_Compound) NBT_Tag.make(entityIs);
			entityIs.close();
		}

		
		if (is_anvil) {
			if (root.getElement("DataVersion") != null && root.getElement("DataVersion").ID() == 3) {
				chunkVer = ((TAG_Int)root.getElement("DataVersion")).value;
			} else {
				//Log.debug(String.format("Couldn't get chunk DataVersion!"));
				chunkVer = 0;//Integer.MAX_VALUE;
			}
		} else {
			chunkVer = 0;
		}
		
		block_image=null;
		height_image=null;
	}

	/**
	 * Prints the description and contents of the chunk.
	 */
	public String toString()
	{
		return "Chunk:\n"+root.toString();
	}

	public static Point getChunkPos(int x, int z)
	{
		Point p = new Point();

		p.x = (x<0) ? ((x-15)/16) : (x/16);
		p.y = (z<0) ? ((z-15)/16) : (z/16);

		return p;
	}

	/**
	 * Small internal class defining the return values of getBlocks method. 
	 * @author danijel
	 *
	 */
	public static class Blocks {
		/**
		 * Main constructor.
		 * @param ymin minimum y level
		 * @param ymax maximum y level
		 */
		public Blocks(int ymin, int ymax)
		{
			int block_num = 16*16*Math.abs(ymax - ymin);
			size = block_num;
			data=new BlockData[block_num];
			biome=new NamespaceID[block_num];
			Arrays.fill(biome, new NamespaceID("minecraft", "plains"));//default to plains
			entities=new LinkedList<TAG_Compound>();
			tile_entities=new LinkedList<TAG_Compound>();
			this.ymin = ymin;
			this.ymax = ymax;
		}
		
		private final int size;
		
		public final int ymin;
		public final int ymax;
		
		/**
		 * Block meta-data.
		 */
		private final BlockData[] data;
		
		public BlockData getBlockData(int x, int y, int z) { 
			int index = getIndex(x, y, z);
			if (index == -1) {
				return null;
			} else {
				return data[index];
			}
		}

		/**
		 * Biome IDSs.
		 */
		private NamespaceID[] biome;
		
		public NamespaceID getBiome(int x, int y, int z) {
			int index = getIndex(x, y, z);
			if (index == -1) {
				return NamespaceID.NULL;
			} else {
				return biome[index];
			}
		}
		
		private int getIndex(int x, int y, int z) {
			if (x < 0 || x > 15 || z < 0 || z > 15) {
				throw new IllegalArgumentException("Invalid relative chunk coordinate");
			}
			if (y < ymin || y >= ymax) {
				return -1;
			} else {
				return x + (z * 16) + ((y - ymin) * 16) * 16;
			}
		}

		/**
		 * Entities.
		 */
		public List<TAG_Compound> entities;

		/**
		 * Tile entities.
		 */
		public List<TAG_Compound> tile_entities;
	}

	/**
	 * Private method for retrieving block data from within chunk data structure.
	 * @return block data as a byte array
	 */
	public Blocks getBlocks()
	{
		Blocks ret=null;
		
		if(is_anvil) {
			TAG_List sections;
			if (chunkVer >= 2844) {// >= 21w43a
				sections = (TAG_List) root.getElement("sections");
			} else {
				TAG_Compound level = (TAG_Compound) root.getElement("Level");
				sections = (TAG_List) level.getElement("Sections");
			}
			if (sections == null) {
				return new Blocks(0, 256);
			}
			
			int ymin = getYMin();
			int ymax = getYMax();
			
			ret = new Blocks(ymin, ymax);
			
			for(NBT_Tag section_t: sections.elements) {
				TAG_Compound section = (TAG_Compound) section_t;
				TAG_Byte yval = (TAG_Byte) section.getElement("Y");
				
				int base=((yval.value*16)-ymin)*16*16;
				
				SectionBlocks secBlocks = getSectionBlocks(section);
				if (secBlocks != null) {
					System.arraycopy(secBlocks.data, 0, ret.data, base, secBlocks.data.length);
					System.arraycopy(secBlocks.biomes, 0, ret.biome, base, secBlocks.biomes.length);
				}
			}
			
			if (chunkVer < 2834) {// < 21w37a newer biomes are in pallet format same as blocks
				TAG_Compound level = (TAG_Compound) root.getElement("Level");
				TAG_Int_Array tagBiomes;
				if (chunkVer >= 1466) {// >= 18w06a
					tagBiomes = (TAG_Int_Array) level.getElement("Biomes");
				} else {
					TAG_Byte_Array tagByteBiomes = (TAG_Byte_Array) level.getElement("Biomes");
					int[] biomes = new int[tagByteBiomes.data.length];
					for (int i = 0; i < tagByteBiomes.data.length; i++) {
						biomes[i] = tagByteBiomes.data[i];
					}
					tagBiomes = new TAG_Int_Array("Biomes", biomes);
				}
				
				if(tagBiomes!=null && tagBiomes.data.length > 0) {
					for(int x = 0; x < 16; x++) {
						for (int z = 0; z < 16; z++) {
							for (int y = 0; y < ymax - ymin; y++) {
								int biome;
								if (chunkVer >= 2203) {// >= 19w36a
									biome = tagBiomes.data[x/4 + (z/4)*4 + (y/4)*4*4];
								} else {
									biome = tagBiomes.data[x+z*16];
								}
								ret.biome[ret.getIndex(x, y+ymin, z)] = IDConvert.convertBiome(biome);
							}
						}
					}
				}
			}
		} else {
			TAG_Compound level = (TAG_Compound) root.getElement("Level");
			TAG_Byte_Array blocks = (TAG_Byte_Array) level.getElement("Blocks");
			TAG_Byte_Array data = (TAG_Byte_Array) level.getElement("Data");
			
			ret= new Blocks(0, 128);
			short[] oldIDs = new short[ret.size];
			byte[] oldData = new byte[ret.size];
			
			for(int i=0; i<blocks.data.length; i++)
				oldIDs[i] = (short) Byte.toUnsignedInt(blocks.data[i]);
			
			for(int i=0; i<data.data.length; i++)
			{
				int val = Byte.toUnsignedInt(data.data[i]);
				byte add1=(byte)(val&0x0f);
				byte add2=(byte)(val>>>4);
				oldData[2*i]=add1;
				oldData[2*i+1]=add2;
			}
			// reorder index from YZX to XZY
			for (int x = 0; x < 16; x++) {
				for (int z = 0; z < 16; z++) {
					for (int y = 0; y < 128; y++) {
						int oldInd = y+z*128+x*128*16;
						int newInd = ret.getIndex(x, y, z);
						ret.data[newInd] = IDConvert.convertBlock(oldIDs[oldInd], oldData[oldInd]);
					}
				}
			}
		}
		
		if (chunkVer < 2844) {// < 21w43a
			TAG_Compound level = (TAG_Compound) root.getElement("Level");
			TAG_List chunk_entities = (TAG_List) level.getElement("Entities");
			if(chunk_entities!=null && chunk_entities.elements.length>0) {
				for(int i=0; i<chunk_entities.elements.length; i++) {
					ret.entities.add((TAG_Compound)chunk_entities.elements[i]);
				}
			}
		}
		if (entities_root != null) {
			TAG_List entities = (TAG_List) entities_root.getElement("Entities");
			if(entities!=null && entities.elements.length>0) {
				for(int i=0; i<entities.elements.length; i++) {
					ret.entities.add((TAG_Compound)entities.elements[i]);
				}
			}
		}
		
		TAG_List tile_entities;
		if (chunkVer >= 2844) {// >= 21w43a
			tile_entities = (TAG_List) root.getElement("block_entities");
		} else {
			TAG_Compound level = (TAG_Compound) root.getElement("Level");
			tile_entities = (TAG_List) level.getElement("TileEntities");
		}
		if(tile_entities!=null && tile_entities.elements.length>0) {
			for(int i=0; i<tile_entities.elements.length; i++) {
				ret.tile_entities.add((TAG_Compound)tile_entities.elements[i]);
			}
		}

		return ret;
	}
	
	@CheckForNull
	SectionBlocks getSectionBlocks(TAG_Compound section) {
		SectionBlocks sectionBlocks = new SectionBlocks();
		boolean hasData = false;
		if (chunkVer >= 1451) {// >= 1.13/17w47a
			hasData |= sectionBlocks.fillBlocks(section);
			
			if (chunkVer >= 2834) {// >= 21w37a Biomes changed format
				hasData |= sectionBlocks.fillBiomes(section);
			}
		} else {// <= 1.12
			hasData |= sectionBlocks.fillBlocksPre1451(section);
		}
		return hasData ? sectionBlocks : null;
	}
	
	class SectionBlocks {
		final BlockData[] data;
		final NamespaceID[] biomes;
		
		SectionBlocks() {
			int size = 16 * 16 * 16;
			data = new BlockData[size];
			biomes = new NamespaceID[size];
			Arrays.fill(biomes, new NamespaceID("minecraft", "plains"));//default to plains
		}
		
		private long calculatePaletteIndex(int index, int bitCount, long[] dataArray) {
			long blockPid;
			int perLong = 64 / bitCount;
			int longInd = index / perLong;
			int longSubInd = index % perLong;
			long lvalue = dataArray[longInd];
			long shifted = lvalue >>> (longSubInd * bitCount);
			blockPid = shifted & (-1L >>> (64 - bitCount));
			return blockPid;
		}
		
		private int bitsForInt(int value) {
			int bits = 0;
			while (value > 0) {
				bits++;
				value = value >> 1;
			}
			return bits;
		}
		
		/**
		 * Fills in the data array with block data
		 * @return false if this was not completed */
		boolean fillBlocks(TAG_Compound section) {
			TAG_List tagBlockPalette;
			TAG_Long_Array tagBlockStates;
			if (chunkVer >= 2834) {// >= 21w37a
				TAG_Compound tagBlockStatesComp = (TAG_Compound) section.getElement("block_states");
				if (tagBlockStatesComp == null) {
					return false;
				}
				tagBlockPalette = (TAG_List) tagBlockStatesComp.getElement("palette");
				tagBlockStates = (TAG_Long_Array) tagBlockStatesComp.getElement("data");
			} else {
				tagBlockPalette = (TAG_List) section.getElement("Palette");
				tagBlockStates = (TAG_Long_Array) section.getElement("BlockStates");
			}
			
			if (tagBlockPalette == null) {
				// palette is null, section must be empty
				return false;
			}
			if (tagBlockStates == null) {
				if (tagBlockPalette.elements.length >= 1) {
					// no state list but a palette indicates the whole section is filled with a single block
					TAG_Compound blockTag = (TAG_Compound)tagBlockPalette.elements[0];
					String blockName = ((TAG_String)blockTag.getElement("Name")).value;
					if (blockName == null) {
						Log.debug("No block name in section palette tag!");
						return false;
					}
					
					BlockData block = new BlockData(NamespaceID.fromString(blockName));
					for (int i = 0; i < 4096; i++) {
						data[i] = block;
					}
					return true;
				}
				return false;
			}
			
			int blockBits = Math.max(bitsForInt(tagBlockPalette.elements.length - 1), 4); // Minimum of 4 bits.
			for (int i = 0; i < 4096; i++) {
				long blockPid;
				if (chunkVer >= 2529) {// >= 20w17a
					blockPid = calculatePaletteIndex(i, blockBits, tagBlockStates.data);
				}
				else {
					BitSet blockBitArr = BitSet.valueOf(tagBlockStates.data).get(i*blockBits, (i+1)*blockBits);
					if (blockBitArr.isEmpty()) {
						blockPid = 0;
					} else {
						blockPid = blockBitArr.toLongArray()[0];
					}
				}
				
				TAG_Compound blockTag = (TAG_Compound)tagBlockPalette.elements[(int)blockPid];
				String blockName = ((TAG_String)blockTag.getElement("Name")).value;
				if (blockName == null) {
					Log.debug("No block name!");
					continue;
				}
				
				BlockData block = new BlockData(NamespaceID.fromString(blockName));
				TAG_Compound propertiesTag = (TAG_Compound)blockTag.getElement("Properties");
				if (propertiesTag != null) {
					for (NBT_Tag tag : propertiesTag.elements) {
						TAG_String propTag = (TAG_String)tag;
						block.state.put(propTag.getName(), propTag.value);
					}
				}
				
				if (block.getInfo().getActWaterlogged()) {
					block.state.putIfAbsent("waterlogged", "true");
					//Log.debug("added waterlogged to: "+blockName.value);
				}
				
				data[i] = block;
			}
			return true;
		}
		
		/**
		 * Fills in the biome array with biome data
		 * @return false if this was not completed */
		boolean fillBiomes(TAG_Compound section) {
			TAG_Compound tagBlockStatesComp = (TAG_Compound) section.getElement("biomes");
			if (tagBlockStatesComp == null) {
				return false;
			}
			TAG_List tagBiomePalette = (TAG_List) tagBlockStatesComp.getElement("palette");
			TAG_Long_Array tagBiomeStates = (TAG_Long_Array) tagBlockStatesComp.getElement("data");
			if (tagBiomePalette == null) {
				return false;
			}
			if (tagBiomePalette.elements.length == 1 || tagBiomeStates == null || tagBiomeStates.data.length <= 1) {
				if (tagBiomePalette.elements.length >= 1) {
					String biomeName = ((TAG_String) tagBiomePalette.elements[0]).value;
					NamespaceID biome = NamespaceID.fromString(biomeName);
					for (int i = 0; i < 4096; i++) {
						biomes[i] = biome;
					}
					return true;
				}
				return false;
			}
			int biomeBits = bitsForInt(tagBiomePalette.elements.length - 1);
			for (int i = 0; i < 64; i++) {
				long biomePid = calculatePaletteIndex(i, biomeBits, tagBiomeStates.data);
				
				String biomeName = ((TAG_String) tagBiomePalette.elements[(int) biomePid]).value;
				NamespaceID biome = NamespaceID.fromString(biomeName);
				//Copy biome into 4x4x4 cube
				for (int x = 0; x < 4; x++) {
					for (int y = 0; y < 4; y++) {
						for (int z = 0; z< 4; z++) {
							int baseInd = ((i%4)*4) + ((i/4)*16*4)%(16*16) + ((i/(4*4))*16*16*4);
							int index = baseInd + (x + z*16 + y*16*16);
							biomes[index] = biome;
						}
					}
				}
			}
			return true;
		}
		
		/**
		 * Fills in the data array with block data
		 * @return false if this was not completed */
		boolean fillBlocksPre1451(TAG_Compound section) {
			short[] oldIDs = new short[4096];
			byte[] oldData = new byte[4096];
			TAG_Byte_Array tagData = (TAG_Byte_Array) section.getElement("Data");
			TAG_Byte_Array tagBlocks = (TAG_Byte_Array) section.getElement("Blocks");
			TAG_Byte_Array tagAdd = (TAG_Byte_Array) section.getElement("Add");
			for(int i=0; i<tagBlocks.data.length; i++)
				oldIDs[i] = (short) Byte.toUnsignedInt(tagBlocks.data[i]);
			
			if (tagAdd != null) {
				for(int i = 0; i < tagAdd.data.length; i++) {
					int val = Byte.toUnsignedInt(tagAdd.data[i]);
					short add1 = (short) (val&0x0f);
					short add2 = (short) (val>>>4);
					oldIDs[2*i] += (short) (add1<<8);
					oldIDs[2*i+1] += (short) (add2<<8);
				}
			}
			
			for (int i = 0; i < tagData.data.length; i++) {
				int val = Byte.toUnsignedInt(tagData.data[i]);
				byte add1=(byte)(val&0x0f);
				byte add2=(byte)(val>>>4);
				oldData[2*i]=add1;
				oldData[2*i+1]=add2;
			}
			for (int i = 0; i < 4096; i++) {
				data[i] = IDConvert.convertBlock(oldIDs[i], oldData[i]);
			}
			return true;
		}
	}
	
	public int getYMin() {
		return getYMinMax()[0];
	}
	public int getYMax() {
		return getYMinMax()[1];
	}
	
	@Nonnull
	private int[] getYMinMax() {
		if (yMinMax != null && yMinMax.length == 2) {
			return yMinMax;
		}
		if (is_anvil) {
			TAG_List sections;
			if (chunkVer >= 2844) {
				sections = (TAG_List) root.getElement("sections");
			} else {
				TAG_Compound level = (TAG_Compound) root.getElement("Level");
				sections = (TAG_List) level.getElement("Sections");
			}
			if (sections == null) {
				yMinMax = new int[] {0, 256};
				return yMinMax;
			}
			
			int ymin=Integer.MAX_VALUE;
			int ymax=Integer.MIN_VALUE;
			for(NBT_Tag section: sections.elements)
			{
				TAG_Compound c_section = (TAG_Compound) section;
				TAG_Byte yval = (TAG_Byte) c_section.getElement("Y");
				if (c_section.getElement("block_states") != null || c_section.getElement("BlockStates") != null || c_section.getElement("Blocks") != null) {
					ymin= Math.min(ymin, yval.value);
					ymax= Math.max(ymax, yval.value);
				}
			}
			ymin=ymin*16;
			ymax=(ymax+1)*16;
			yMinMax = new int[] {ymin, ymax};
		} else {
			yMinMax = new int[] {0, 128};
		}
		return yMinMax;
	}

	/**
	 * Renders the block and height images.
	 * @param floor floor boundary
	 * @param ceiling ceiling boundary
	 */
	public BlockDataPos[] renderImages(int floor, int ceiling, boolean fastmode)
	{
		int width = 4 * 16;
		int height = 4 * 16;
		block_image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		height_image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

		Graphics2D gb = block_image.createGraphics();
		gb.setColor(Color.black);
		gb.fillRect(0, 0, width, height);

		Graphics2D gh = height_image.createGraphics();
		gh.setColor(Color.black);
		gh.fillRect(0, 0, width, height);

		Blocks bd=getBlocks();

		int drawYMax = ceiling;
		int drawYMin = floor;
		if(floor>bd.ymax)
			return null;
		if(ceiling>bd.ymax)
			ceiling=bd.ymax;
		if(floor>=ceiling)
			floor=ceiling-1;


		BlockDataPos[] topBlocks = new BlockDataPos[16*16];
		int[] himage = new int[16*16];
		
		Arrays.fill(himage, drawYMin);
		
		int x,y,z;
		for(z = 0; z < 16; z++)
		{
			for(x = 0; x < 16; x++)
			{
				for(y = floor; y < ceiling; y++)
				{
					if (Thread.currentThread().isInterrupted())
						return null;
					
					NamespaceID blockBiome = bd.getBiome(x, y, z);
					BlockData blockData = bd.getBlockData(x, y, z);
					
					if(blockData != null && !blockData.getInfo().getOcclusion().equals(Occlusion.NONE)) {
						topBlocks[z*16+x] = new BlockDataPos(new BlockPos(x, y, z), blockData, blockBiome);
						himage[z*16+x]=y;
					}
				}
			}
		}


		for(z = 0; z < 16; z++)
		{
			for(x = 0; x < 16; x++)
			{
				if (Thread.currentThread().isInterrupted())
					return null;
				
				BlockDataPos block = topBlocks[z*16+x];
				
				if(block != null) {
					BlockInfo type = block.data.getInfo();
					if (type.getModel().getClass() != None.class) {
						gb.setColor(type.getPreviewColor(block.data,block.biome));
						gb.fillRect(x*4, z*4, 4, 4);
					}
				}
			}
		}

		if(!fastmode){
			for(z = 0; z < 16; z++)
			{
				for(x = 0; x < 16; x++)
				{
					if (Thread.currentThread().isInterrupted())
						return null;
					
					float a = himage[z*16+x] - drawYMin;
					float b = drawYMax - drawYMin;
					float r = Math.max(0, Math.min(1, a / b));
					gh.setColor(new Color(r,r,r));
					gh.fillRect(x*4, z*4, 4, 4);
				}
			}
		}
		return topBlocks;
	}

	/**
	 * Retrieves block image. Must run renderImages first!
	 * @return image of topmost blocks
	 */
	public BufferedImage getBlockImage()
	{
		return block_image;
	}

	/**
	 * Retrieves height image. Must run renderImages first!
	 * @return image of topmost block heights
	 */
	public BufferedImage getHeightImage()
	{
		return height_image;
	}
}
