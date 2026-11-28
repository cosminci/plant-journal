import type * as Journal from "./Journal";

export interface PlantClient {
  getDormancy(plant: Journal.PlantId): Promise<Journal.DormancyResult>;
  editDormancy(plant: Journal.PlantId, edit: Journal.DormancyEdit): Promise<Journal.DormancyResult>;
  getPlants(status?: Journal.PlantStatus): Promise<Journal.GetPlantsResult>;
  createPlant(details: Journal.NewPlantDetails): Promise<Journal.CreatePlantResult>;
  getArchivedCount(): Promise<Journal.GetArchivedCountResult>;
  archivePlant(plant: Journal.PlantId): Promise<Journal.ArchivePlantResult>;
  editPlant(
    plant: Journal.PlantId,
    details: Journal.NewPlantDetails,
  ): Promise<Journal.EditPlantResult>;
}
